package dev.mcpfabric.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.ServerHolder;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.MainThread;
import dev.mcpfabric.bridge.RpcContext;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.handlers.support.CommandRunner;
import dev.mcpfabric.handlers.support.Gates;
import dev.mcpfabric.handlers.support.Levels;
import dev.mcpfabric.handlers.support.SupervisedCalls;
import dev.mcpfabric.handlers.support.SupervisedCalls.Done;
import dev.mcpfabric.supervision.ActionLedger;
import dev.mcpfabric.supervision.ActionLedger.Outcome;
import dev.mcpfabric.supervision.Admission;
import dev.mcpfabric.supervision.SupervisedRules;
import dev.mcpfabric.supervision.Supervisor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The supervised mode (#27), shared part: status, the session, STOP, and the narrow server-side
 * operations (read one block, set one block with a precondition, run one allowlisted command) plus
 * reconciling actions whose outcome is not known. The client adds input samples and entering or
 * leaving an approved save.
 *
 * <p>Every operation answers with its {@code outcome}: {@code applied}, {@code not_applied},
 * {@code interrupted}, {@code pending}, {@code uncertain} or {@code acknowledged}. Refusals before a
 * grant (session, open actions, availability, bad params) are errors.
 */
public final class SupervisedHandlers {
	private SupervisedHandlers() {}

	public static void register(RpcRouter router) {
		Supervisor sv = McpFabric.supervisor();
		sv.operation("read_block", SupervisedHandlers::noServer);
		sv.operation("set_block", SupervisedHandlers::noServer);
		sv.operation("command", () -> {
			String reason = noServer();
			if (reason != null) return reason;
			return McpFabric.config().supervisedCommands.isEmpty() ? "supervisedCommands in mcpfabric.config.json is empty" : null;
		});

		router.register("supervised.status", ctx -> {
			Gates.supervised();
			return status(sv);
		});

		// Called by the MCP server, not the model: the session id stays in the MCP server.
		router.register("supervised.begin", ctx -> {
			Gates.supervised();
			JsonObject o = new JsonObject();
			o.addProperty("session", sv.sessions().open());
			return o;
		});

		router.register("supervised.end", ctx -> {
			Gates.supervised();
			JsonObject o = new JsonObject();
			o.addProperty("closed", sv.sessions().close(ctx.getString("session")));
			return o;
		});

		// Not gated: stopping must always work, and it only takes authority away.
		router.register("supervised.stop", ctx -> {
			sv.stop();
			return Json.ok("stopped: no grant issued before this admits anything, and supervised input is released. "
					+ "Effects that were already admitted are not rolled back.");
		});

		router.register("supervised.readBlock", ctx -> {
			Gates.supervised();
			BlockTarget t = blockTarget(ctx);
			Supervisor.Request req = SupervisedCalls.request(ctx, "read_block", t, t.text());
			MinecraftServer server = server();
			return SupervisedCalls.run(server, req.actionId(), () -> {
				ServerLevel level = Levels.resolve(server, t.dimension());
				Admission a = sv.authority().admitAction(req.grant(), t);
				if (!a.admitted()) return Done.refused(a);
				BlockPos pos = t.pos();
				if (!level.hasChunkAt(pos)) return Done.of(Outcome.NOT_APPLIED, "chunk_not_loaded", null);
				JsonObject d = new JsonObject();
				d.add("block", Levels.describeBlock(level, pos, level.getBlockState(pos)));
				return Done.of(Outcome.APPLIED, null, d);
			});
		});

		router.register("supervised.setBlock", ctx -> {
			Gates.supervised();
			BlockTarget t = blockTarget(ctx);
			String block = rule(() -> SupervisedRules.block(ctx.getString("block")));
			String expect = rule(() -> SupervisedRules.blockId(ctx.getString("expect")));
			String text = t.text() + " " + expect + " -> " + block;
			Supervisor.Request req = SupervisedCalls.request(ctx, "set_block", t, text);
			MinecraftServer server = server();
			String wanted = SupervisedRules.baseId(block);
			return SupervisedCalls.run(server, req.actionId(), () -> {
				ServerLevel level = Levels.resolve(server, t.dimension());
				Admission a = sv.authority().admitAction(req.grant(), t);
				if (!a.admitted()) return Done.refused(a);
				BlockPos pos = t.pos();
				if (!level.hasChunkAt(pos)) return Done.of(Outcome.NOT_APPLIED, "chunk_not_loaded", null);
				String before = Levels.blockId(level.getBlockState(pos));
				JsonObject d = new JsonObject();
				d.addProperty("before", before);
				if (!before.equals(expect)) {
					return Done.of(Outcome.NOT_APPLIED, "precondition_failed: the block is " + before + ", not " + expect, d);
				}
				CommandRunner.Result r = CommandRunner.run(server, level,
						"setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block);
				String after = Levels.blockId(level.getBlockState(pos));
				d.addProperty("after", after);
				d.add("output", r.toJson().get("output"));
				ActionLedger.Reconciler readback = () -> readBack(server, t, wanted, expect);
				if (after.equals(wanted)) return Done.of(Outcome.APPLIED, r.success() ? null : "already in place", d);
				if (after.equals(expect) && !r.success()) return Done.of(Outcome.NOT_APPLIED, "command_failed", d);
				return new Done(Outcome.UNCERTAIN, "readback_mismatch", d, readback);
			});
		});

		router.register("supervised.command", ctx -> {
			Gates.supervised();
			String command = rule(() -> SupervisedRules.command(ctx.getString("command"), McpFabric.config().supervisedCommands));
			Supervisor.Request req = SupervisedCalls.request(ctx, "command", command, command);
			MinecraftServer server = server();
			return SupervisedCalls.run(server, req.actionId(), () -> {
				Admission a = sv.authority().admitAction(req.grant(), command);
				if (!a.admitted()) return Done.refused(a);
				CommandRunner.Result r = CommandRunner.run(server, command);
				JsonObject d = new JsonObject();
				d.addProperty("resultValue", r.resultValue());
				d.add("output", r.toJson().get("output"));
				return r.success() ? Done.of(Outcome.APPLIED, null, d) : Done.of(Outcome.NOT_APPLIED, "command_failed", d);
			});
		});

		router.register("supervised.reconcile", ctx -> {
			Gates.supervised();
			long seq = ctx.optLong("seq", 0);
			if (seq <= 0) throw RpcException.badRequest("Param 'seq' must be a positive integer.");
			sv.checkSession(ctx.optString("session", null), seq);
			String id = ctx.getString("actionId");
			ActionLedger ledger = sv.ledger();
			ActionLedger.Entry entry = ledger.get(id);
			if (entry == null) throw RpcException.notFound("No supervised action " + id + " (only recent actions are kept).");
			if (entry.outcome().open()) {
				ActionLedger.Reconciler reconciler = ledger.reconciler(id);
				if (reconciler != null) {
					ActionLedger.Result r;
					try {
						r = reconciler.check();
					} catch (RpcException e) {
						r = new ActionLedger.Result(entry.outcome(), "reconcile failed: " + e.code());
					} catch (Exception e) {
						r = new ActionLedger.Result(entry.outcome(), "reconcile failed: " + e.getClass().getSimpleName());
					}
					if (r.outcome().open()) {
						ledger.settle(id, r.outcome(), r.reason(), reconciler);
					} else {
						ledger.settle(id, r.outcome(), r.reason());
					}
				}
				if (ledger.get(id).outcome().open() && ctx.optBool("acknowledge", false)) {
					ledger.settle(id, Outcome.ACKNOWLEDGED, "outcome unknown, accepted by the caller");
				}
				SupervisedCalls.emit(ledger.get(id));
			}
			return SupervisedCalls.result(ledger.get(id), null);
		});
	}

	// --- helpers ---------------------------------------------------------------------------------

	/** One block position in one dimension: the exact target of a block operation. */
	private record BlockTarget(String dimension, int x, int y, int z) {
		BlockPos pos() {
			return new BlockPos(x, y, z);
		}

		String text() {
			return (dimension == null ? "minecraft:overworld" : dimension) + " " + x + " " + y + " " + z;
		}
	}

	private static BlockTarget blockTarget(RpcContext ctx) throws RpcException {
		String dimension = ctx.optString("dimension", null);
		if (dimension != null && dimension.isBlank()) dimension = null;
		return new BlockTarget(dimension, ctx.getInt("x"), ctx.getInt("y"), ctx.getInt("z"));
	}

	private static ActionLedger.Result readBack(MinecraftServer server, BlockTarget t, String wanted, String expect) throws RpcException {
		return MainThread.call(server, McpFabric.config().callTimeoutMs, () -> {
			ServerLevel level = Levels.resolve(server, t.dimension());
			if (!level.hasChunkAt(t.pos())) return new ActionLedger.Result(Outcome.UNCERTAIN, "chunk_not_loaded");
			String now = Levels.blockId(level.getBlockState(t.pos()));
			if (now.equals(wanted)) return new ActionLedger.Result(Outcome.APPLIED, "read back " + now);
			if (now.equals(expect)) return new ActionLedger.Result(Outcome.NOT_APPLIED, "read back " + now);
			return new ActionLedger.Result(Outcome.UNCERTAIN, "read back " + now + ", neither " + expect + " nor " + wanted);
		});
	}

	private static MinecraftServer server() throws RpcException {
		MinecraftServer server = ServerHolder.get();
		if (server == null) throw RpcException.noServer();
		return server;
	}

	private static String noServer() {
		return ServerHolder.isPresent() ? null : "needs an integrated or dedicated server";
	}

	@FunctionalInterface
	private interface Rule {
		String check() throws RpcException;
	}

	/** A {@link SupervisedRules} check, refused as {@code not_allowed}. */
	private static String rule(Rule rule) throws RpcException {
		try {
			return rule.check();
		} catch (IllegalArgumentException e) {
			throw new RpcException("not_allowed", e.getMessage());
		}
	}

	private static JsonObject status(Supervisor sv) {
		JsonObject o = new JsonObject();
		o.addProperty("enabled", true);
		o.addProperty("session", sv.sessions().active());
		JsonObject ops = new JsonObject();
		for (String op : Supervisor.OPERATIONS) {
			JsonObject e = new JsonObject();
			String reason = sv.unavailable(op);
			e.addProperty("available", reason == null);
			if (reason != null) e.addProperty("reason", reason);
			ops.add(op, e);
		}
		o.add("operations", ops);
		JsonArray open = new JsonArray();
		sv.ledger().openEntries().forEach(e -> open.add(SupervisedCalls.entryJson(e)));
		o.add("open", open);
		JsonArray recent = new JsonArray();
		sv.ledger().recent(10).forEach(e -> recent.add(SupervisedCalls.entryJson(e)));
		o.add("recent", recent);
		JsonArray worlds = new JsonArray();
		McpFabric.config().supervisedWorlds.forEach(w -> worlds.add("sp:" + w));
		o.add("approvedWorlds", worlds);
		JsonArray commands = new JsonArray();
		McpFabric.config().supervisedCommands.forEach(commands::add);
		o.add("allowedCommands", commands);
		JsonObject limits = new JsonObject();
		limits.addProperty("grantTtlMs", Supervisor.GRANT_TTL_NANOS / 1_000_000);
		o.add("limits", limits);
		sv.statusParts().forEach(part -> part.accept(o));
		return o;
	}
}
