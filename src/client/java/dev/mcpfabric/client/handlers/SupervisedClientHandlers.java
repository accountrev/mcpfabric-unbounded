package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.handlers.support.Gates;
import dev.mcpfabric.handlers.support.Levels;
import dev.mcpfabric.handlers.support.SupervisedCalls;
import dev.mcpfabric.handlers.support.SupervisedCalls.Done;
import dev.mcpfabric.supervision.ActionLedger;
import dev.mcpfabric.supervision.ActionLedger.Outcome;
import dev.mcpfabric.supervision.Admission;
import dev.mcpfabric.supervision.Cardinal;
import dev.mcpfabric.supervision.OwnedInputs;
import dev.mcpfabric.supervision.SupervisedAuthority;
import dev.mcpfabric.supervision.Supervisor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The supervised mode (#27), client part:
 * <ul>
 *   <li>{@code supervised.input} — hold one movement key (forward, back, left or right) for a few
 *       ticks, then release it;</li>
 *   <li>{@code supervised.enterWorld} — open a save listed in {@code supervisedWorlds}, from the menus;</li>
 *   <li>{@code supervised.leaveWorld} — save and quit the singleplayer world the player is in.</li>
 * </ul>
 * The client tick binds grants to the world and dimension the player is in, so entering, leaving or
 * changing dimension fences them. Keys are released only while the automation still owns them: a
 * key that is already down belongs to the player and is never taken, and a key that goes up while the
 * automation holds it (the player, or a screen opening) ends the sample.
 */
public final class SupervisedClientHandlers {
	/** The longest input sample, in client ticks (half a second). */
	public static final int MAX_SAMPLE_TICKS = 10;

	// Render thread only.
	private static final OwnedInputs<Cardinal> INPUTS = new OwnedInputs<>();
	private static Sample sample;
	private static boolean contextKnown;
	private static ClientLevel lastLevel;

	// Written by the client tick, read by supervised.status on a bridge thread.
	private static volatile boolean inWorld;
	private static volatile boolean singleplayer;
	private static volatile Set<Cardinal> ownedSnapshot = Set.of();
	private static volatile Set<Cardinal> pendingSnapshot = Set.of();

	private SupervisedClientHandlers() {}

	private static final class Sample {
		final String actionId;
		final SupervisedAuthority.Grant grant;
		final Cardinal direction;
		final int ticks;
		final CompletableFuture<JsonObject> done = new CompletableFuture<>();
		Vec3 from;
		boolean started;
		int held;

		Sample(String actionId, SupervisedAuthority.Grant grant, Cardinal direction, int ticks) {
			this.actionId = actionId;
			this.grant = grant;
			this.direction = direction;
			this.ticks = ticks;
		}
	}

	public static void register(RpcRouter router) {
		Supervisor sv = McpFabric.supervisor();
		sv.operation("input", () -> inWorld ? null : "not in a world");
		sv.operation("enter_world", () -> {
			if (McpFabric.config().supervisedWorlds.isEmpty()) return "supervisedWorlds in mcpfabric.config.json is empty";
			return inWorld ? "already in a world" : null;
		});
		sv.operation("leave_world", () -> {
			if (!inWorld) return "not in a world";
			return singleplayer ? null : "only singleplayer saves can be left";
		});
		sv.onStop(() -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null) mc.execute(() -> endSample("stopped"));
		});
		sv.statusPart(o -> {
			JsonObject in = new JsonObject();
			in.add("held", names(ownedSnapshot));
			in.add("pendingCleanup", names(pendingSnapshot));
			in.addProperty("maxTicks", MAX_SAMPLE_TICKS);
			o.add("input", in);
		});

		router.register("supervised.input", ctx -> {
			Gates.supervised();
			Cardinal direction = direction(ctx.getString("direction"));
			int ticks = ctx.optInt("ticks", 4);
			if (ticks < 1 || ticks > MAX_SAMPLE_TICKS) {
				throw RpcException.badRequest("ticks must be 1.." + MAX_SAMPLE_TICKS + ".");
			}
			String text = direction.name().toLowerCase(Locale.ROOT) + " for " + ticks + " ticks";
			Supervisor.Request req = SupervisedCalls.request(ctx, "input", direction, text);
			Sample s = new Sample(req.actionId(), req.grant(), direction, ticks);
			JsonObject started = SupervisedCalls.run(client(), req.actionId(), () -> {
				Admission a = sv.authority().admitAction(req.grant(), direction);
				if (!a.admitted()) return Done.refused(a);
				Minecraft mc = ClientMc.mc();
				LocalPlayer p = mc.player;
				if (p == null || mc.level == null) return Done.of(Outcome.NOT_APPLIED, "not_in_world", null);
				if (ContainerHandlers.currentScreen() != null) return Done.of(Outcome.NOT_APPLIED, "screen_open", null);
				if (sample != null) return Done.of(Outcome.NOT_APPLIED, "busy", null);
				if (BotController.get().drivingKeys()) return Done.of(Outcome.NOT_APPLIED, "movement_in_progress", null);
				if (key(mc.options, direction).isDown()) return Done.of(Outcome.NOT_APPLIED, "player_holds_key", null);
				s.from = p.position();
				sample = s;
				return Done.of(Outcome.PENDING, "holding", null);
			});
			if (!Outcome.PENDING.id().equals(started.get("outcome").getAsString())) return started;
			try {
				return s.done.get(ticks * 50L + McpFabric.config().callTimeoutMs, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				sv.ledger().settleIf(req.actionId(), Outcome.PENDING, Outcome.UNCERTAIN, "timeout");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				sv.ledger().settleIf(req.actionId(), Outcome.PENDING, Outcome.UNCERTAIN, "interrupted");
			} catch (ExecutionException e) {
				sv.ledger().settleIf(req.actionId(), Outcome.PENDING, Outcome.UNCERTAIN, "internal");
			}
			// The client tick still ends the sample and settles the action: reconcile reads the result.
			return SupervisedCalls.result(sv.ledger().get(req.actionId()), null);
		});

		router.register("supervised.enterWorld", ctx -> {
			Gates.supervised();
			String worldId = ctx.getString("worldId");
			if (!worldId.startsWith("sp:")) {
				throw new RpcException("not_allowed", "Supervised mode only enters singleplayer saves (sp:<folder>).");
			}
			String folder = worldId.substring(3);
			if (!McpFabric.config().supervisedWorlds.contains(folder)) {
				throw new RpcException("not_allowed", "Save '" + folder + "' is not in supervisedWorlds in mcpfabric.config.json.");
			}
			LevelSummary level = SessionScreenHandlers.levelSummaries().stream()
					.filter(l -> l.getLevelId().equals(folder))
					.findFirst()
					.orElseThrow(() -> RpcException.notFound("No saved world with id " + worldId + "; see list_worlds."));
			if (!SessionScreenHandlers.joinable(level)) {
				throw new RpcException("not_joinable", "World " + worldId + " cannot be opened from here.");
			}
			Supervisor.Request req = SupervisedCalls.request(ctx, "enter_world", worldId, worldId);
			return SupervisedCalls.run(client(), req.actionId(), () -> {
				Admission a = sv.authority().admitAction(req.grant(), worldId);
				if (!a.admitted()) return Done.refused(a);
				Minecraft mc = ClientMc.mc();
				if (mc.level != null) return Done.of(Outcome.NOT_APPLIED, "in_world", null);
				if (!SessionScreenHandlers.canJoin(mc, ContainerHandlers.currentScreen())) {
					return Done.of(Outcome.NOT_APPLIED, "busy", null);
				}
				mc.createWorldOpenFlows().openWorld(folder, () -> SessionScreenHandlers.setScreen(mc, new TitleScreen()));
				return new Done(Outcome.PENDING, "opening", null, () -> ClientMc.call(() -> entered(worldId)));
			});
		});

		router.register("supervised.leaveWorld", ctx -> {
			Gates.supervised();
			String worldId = ctx.getString("worldId");
			Supervisor.Request req = SupervisedCalls.request(ctx, "leave_world", worldId, worldId);
			return SupervisedCalls.run(client(), req.actionId(), () -> {
				Admission a = sv.authority().admitAction(req.grant(), worldId);
				if (!a.admitted()) return Done.refused(a);
				Minecraft mc = ClientMc.mc();
				if (mc.level == null) return Done.of(Outcome.NOT_APPLIED, "not_in_world", null);
				if (mc.getSingleplayerServer() == null) return Done.of(Outcome.NOT_APPLIED, "not_singleplayer", null);
				if (!PerceptionHandlers.worldId(mc).equals(worldId)) return Done.of(Outcome.NOT_APPLIED, "in_another_world", null);
				endSample("leaving");
				saveAndQuit(mc);
				return new Done(Outcome.PENDING, "saving and leaving", null, () -> ClientMc.call(SupervisedClientHandlers::left));
			});
		});
	}

	/** End of every client tick, after the {@link BotController}: context, the input sample, cleanup. */
	public static void onClientTick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (!contextKnown || level != lastLevel) {
			contextKnown = true;
			lastLevel = level;
			McpFabric.supervisor().enterContext(level == null ? "menu" : PerceptionHandlers.worldId(mc) + "|" + Levels.dimensionId(level));
			endSample("context_changed");
		}
		inWorld = level != null && mc.player != null;
		singleplayer = mc.getSingleplayerServer() != null;
		tickSample(mc);
		// Retry failed cleanups, but not while a sample holds its key (a drain would release it).
		if (sample == null && !INPUTS.pending().isEmpty()) INPUTS.drain();
		snapshot();
	}

	private static void tickSample(Minecraft mc) {
		Sample s = sample;
		if (s == null) return;
		KeyMapping key = key(mc.options, s.direction);
		if (!s.started) {
			Admission a = McpFabric.supervisor().authority().admitInputSample(s.grant, s.direction);
			if (!a.admitted()) {
				finish(s, Outcome.NOT_APPLIED, a.name().toLowerCase(Locale.ROOT));
				return;
			}
			INPUTS.claim(s.direction, () -> {
				key.setDown(false);
				return !key.isDown();
			});
			key.setDown(true);
			s.started = true;
			return;
		}
		if (!key.isDown()) {
			// Someone else released the key (the player, or a screen that opened): it is theirs now.
			INPUTS.yieldToPlayer(s.direction);
			finish(s, Outcome.INTERRUPTED, "key_released");
			return;
		}
		if (++s.held >= s.ticks) {
			INPUTS.drain();
			finish(s, Outcome.APPLIED, null);
		}
	}

	/** Ends the running sample early and releases every key the automation holds. */
	private static void endSample(String reason) {
		Sample s = sample;
		INPUTS.drain();
		if (s != null) finish(s, s.started ? Outcome.INTERRUPTED : Outcome.NOT_APPLIED, reason);
		snapshot();
	}

	private static void finish(Sample s, Outcome outcome, String reason) {
		if (sample == s) sample = null;
		ActionLedger ledger = McpFabric.supervisor().ledger();
		ledger.settle(s.actionId, outcome, reason);
		JsonObject d = new JsonObject();
		d.addProperty("ticksHeld", s.held);
		LocalPlayer p = Minecraft.getInstance().player;
		if (s.from != null) d.add("from", vec(s.from));
		if (p != null) d.add("to", vec(p.position()));
		ActionLedger.Entry entry = ledger.get(s.actionId);
		SupervisedCalls.emit(entry);
		s.done.complete(SupervisedCalls.result(entry, d));
	}

	private static ActionLedger.Result entered(String worldId) {
		Minecraft mc = ClientMc.mc();
		if (mc.level != null && mc.player != null) {
			return PerceptionHandlers.worldId(mc).equals(worldId)
					? new ActionLedger.Result(Outcome.APPLIED, "in the world")
					: new ActionLedger.Result(Outcome.UNCERTAIN, "in another world");
		}
		String kind = SessionScreenHandlers.kind(ContainerHandlers.currentScreen());
		if (mc.level != null || kind.equals("loading") || kind.equals("connecting")) {
			return new ActionLedger.Result(Outcome.PENDING, "still loading");
		}
		return new ActionLedger.Result(Outcome.NOT_APPLIED, "the world did not open (screen: " + kind + ")");
	}

	private static ActionLedger.Result left() {
		Minecraft mc = ClientMc.mc();
		String kind = SessionScreenHandlers.kind(ContainerHandlers.currentScreen());
		if (mc.level != null || kind.equals("loading")) return new ActionLedger.Result(Outcome.PENDING, "still leaving");
		return new ActionLedger.Result(Outcome.APPLIED, "left the world; that the save reached the disk is not verified");
	}

	/** The pause menu's Save and Quit: stops the integrated server, which saves, and opens the title screen. */
	private static void saveAndQuit(Minecraft mc) {
		//? if >=1.21.9 {
		/*mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
		*///?} else if >=1.21.6 {
		mc.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
		mc.disconnectWithSavingScreen();
		mc.setScreen(new TitleScreen());
		//?} else {
		/*mc.level.disconnect();
		mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
		mc.setScreen(new TitleScreen());
		*///?}
	}

	private static Minecraft client() throws RpcException {
		Minecraft mc = ClientMc.mc();
		if (mc == null) throw RpcException.noClientPlayer();
		return mc;
	}

	private static Cardinal direction(String name) throws RpcException {
		try {
			return Cardinal.valueOf(name.strip().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw RpcException.badRequest("direction must be forward, back, left or right.");
		}
	}

	private static KeyMapping key(Options o, Cardinal direction) {
		return switch (direction) {
			case FORWARD -> o.keyUp;
			case BACK -> o.keyDown;
			case LEFT -> o.keyLeft;
			case RIGHT -> o.keyRight;
		};
	}

	private static void snapshot() {
		EnumSet<Cardinal> owned = EnumSet.noneOf(Cardinal.class);
		for (Cardinal c : Cardinal.values()) {
			if (INPUTS.owns(c)) owned.add(c);
		}
		ownedSnapshot = Set.copyOf(owned);
		pendingSnapshot = INPUTS.pending();
	}

	private static JsonArray names(Set<Cardinal> set) {
		JsonArray a = new JsonArray();
		for (Cardinal c : Cardinal.values()) {
			if (set.contains(c)) a.add(c.name().toLowerCase(Locale.ROOT));
		}
		return a;
	}

	private static JsonObject vec(Vec3 v) {
		JsonObject o = new JsonObject();
		o.addProperty("x", v.x);
		o.addProperty("y", v.y);
		o.addProperty("z", v.z);
		return o;
	}
}
