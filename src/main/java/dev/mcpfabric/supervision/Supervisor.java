package dev.mcpfabric.supervision;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The supervised mode (#27): one session, finite authority and the outcome ledger.
 *
 * <p>A supervised request passes, in order: the session check (current session, growing sequence
 * number), the ledger (no open action), the operation's availability on this side, and gets a grant
 * that expires after {@link #GRANT_TTL_NANOS}. The effect then admits the grant on the game thread,
 * where STOP, a context change, a different target or the expiry turn it down.
 */
public final class Supervisor {
	/** Every supervised operation, in the order {@code supervised.status} lists them. */
	public static final List<String> OPERATIONS = List.of("read_block", "set_block", "command", "input", "enter_world", "leave_world");
	/** How long the game thread has to admit a request. */
	public static final long GRANT_TTL_NANOS = TimeUnit.SECONDS.toNanos(2);

	private final SupervisedAuthority authority;
	private final SupervisorSessions sessions;
	private final ActionLedger ledger = new ActionLedger();
	private final Map<String, Supplier<String>> availability = new ConcurrentHashMap<>();
	private final List<Runnable> stopHooks = new CopyOnWriteArrayList<>();
	private final List<Consumer<JsonObject>> statusParts = new CopyOnWriteArrayList<>();

	public Supervisor() {
		this(new SupervisedAuthority());
	}

	public Supervisor(SupervisedAuthority authority) {
		this.authority = authority;
		this.sessions = new SupervisorSessions(this::stop);
	}

	public SupervisedAuthority authority() {
		return authority;
	}

	public SupervisorSessions sessions() {
		return sessions;
	}

	public ActionLedger ledger() {
		return ledger;
	}

	/** Registers an operation this side implements; {@code unavailableReason} returns null while it can run. */
	public void operation(String name, Supplier<String> unavailableReason) {
		if (!OPERATIONS.contains(name)) throw new IllegalArgumentException("unknown operation " + name);
		availability.put(name, unavailableReason);
	}

	/** Why {@code operation} cannot run right now, or null when it can. */
	public String unavailable(String operation) {
		Supplier<String> reason = availability.get(operation);
		return reason == null ? "not implemented on this side of the bridge" : reason.get();
	}

	/** Runs whenever STOP fences the authority: release what the automation holds. */
	public void onStop(Runnable hook) {
		stopHooks.add(hook);
	}

	/** Adds fields to {@code supervised.status} (the client reports the input it holds). */
	public void statusPart(Consumer<JsonObject> part) {
		statusParts.add(part);
	}

	public List<Consumer<JsonObject>> statusParts() {
		return statusParts;
	}

	/** STOP: fences every grant and runs the stop hooks. Admitted effects are not rolled back. */
	public void stop() {
		authority.stop();
		for (Runnable hook : stopHooks) {
			try {
				hook.run();
			} catch (RuntimeException ignored) {
				// a failing hook must not keep the others from running
			}
		}
	}

	public void enterContext(Object context) {
		authority.enterContext(context);
	}

	/** A granted request: its ledger id and the grant the effect admits. */
	public record Request(String actionId, SupervisedAuthority.Grant grant) {}

	/** Checks a request and grants it, recording the action as pending. */
	public synchronized Request request(String session, long seq, String operation, Object target, String targetText)
			throws RpcException {
		checkSession(session, seq);
		List<ActionLedger.Entry> open = ledger.openEntries();
		if (!open.isEmpty()) {
			JsonArray ids = new JsonArray();
			open.forEach(e -> ids.add(e.id()));
			JsonObject data = new JsonObject();
			data.add("open", ids);
			throw new RpcException("unreconciled", "Action " + open.get(0).id() + " (" + open.get(0).operation()
					+ ") has no known outcome yet: call supervised_reconcile before anything else.", data);
		}
		String reason = unavailable(operation);
		if (reason != null) {
			throw new RpcException("not_available", "Supervised " + operation + " is not available: " + reason + ".");
		}
		SupervisedAuthority.Grant grant;
		try {
			grant = authority.grant(target, GRANT_TTL_NANOS);
		} catch (IllegalStateException e) {
			throw new RpcException("no_context", "The game has not reported its state yet; try again in a moment.");
		}
		return new Request(ledger.open(operation, targetText), grant);
	}

	/** Checks the session and sequence number of a request that needs no grant (reconcile). */
	public void checkSession(String session, long seq) throws RpcException {
		switch (sessions.check(session, seq)) {
			case OK -> {
			}
			case NO_SESSION -> throw new RpcException("no_session", "No supervisor session: the MCP server opens one with supervised.begin.");
			case STALE_SESSION -> throw new RpcException("stale_session",
					"This supervisor session ended or another one took over; the next call opens a new session.");
			case REPLAYED -> throw new RpcException("replayed", "Request sequence number " + seq + " was already used.");
		}
	}
}
