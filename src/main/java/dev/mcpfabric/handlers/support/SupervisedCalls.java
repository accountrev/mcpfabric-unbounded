package dev.mcpfabric.handlers.support;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.MainThread;
import dev.mcpfabric.bridge.RpcContext;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.supervision.ActionLedger;
import dev.mcpfabric.supervision.ActionLedger.Outcome;
import dev.mcpfabric.supervision.Admission;
import dev.mcpfabric.supervision.Supervisor;

import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a supervised effect on the game thread and settles its outcome in the ledger (#27).
 *
 * <p>If the bridge stops waiting before the game thread picked the effect up, the effect is
 * abandoned and the action is {@code not_applied}. If it already started, the action becomes
 * {@code uncertain}, and the effect settles it with its real outcome when it finishes.
 */
public final class SupervisedCalls {
	private static final int NOT_STARTED = 0;
	private static final int STARTED = 1;
	private static final int ABANDONED = 2;

	private SupervisedCalls() {}

	/** What an effect did. {@code details} (may be null) are added to the result. */
	public record Done(Outcome outcome, String reason, JsonObject details, ActionLedger.Reconciler reconciler) {
		public static Done of(Outcome outcome, String reason, JsonObject details) {
			return new Done(outcome, reason, details, null);
		}

		public static Done refused(Admission admission) {
			return of(Outcome.NOT_APPLIED, admission.name().toLowerCase(Locale.ROOT), null);
		}
	}

	/**
	 * An effect. It admits its grant first and acts only when admitted. A {@link RpcException} it
	 * throws means nothing was changed (it must check before it acts).
	 */
	@FunctionalInterface
	public interface Effect {
		Done run() throws RpcException;
	}

	public static Supervisor supervisor() {
		return McpFabric.supervisor();
	}

	/** The session and sequence number every supervised request carries (set by the MCP server). */
	public static Supervisor.Request request(RpcContext ctx, String operation, Object target, String targetText) throws RpcException {
		long seq = ctx.optLong("seq", 0);
		if (seq <= 0) throw RpcException.badRequest("Param 'seq' must be a positive integer.");
		return supervisor().request(ctx.optString("session", null), seq, operation, target, targetText);
	}

	public static JsonObject run(Executor gameThread, String actionId, Effect effect) throws RpcException {
		ActionLedger ledger = supervisor().ledger();
		AtomicInteger phase = new AtomicInteger(NOT_STARTED);
		JsonObject details = null;
		try {
			Done done = MainThread.call(gameThread, McpFabric.config().callTimeoutMs, () -> {
				if (!phase.compareAndSet(NOT_STARTED, STARTED)) return null;
				Done d;
				try {
					d = effect.run();
				} catch (RpcException e) {
					d = Done.of(Outcome.NOT_APPLIED, e.code() + ": " + e.getMessage(), null);
				} catch (RuntimeException e) {
					d = Done.of(Outcome.UNCERTAIN, "internal: " + e.getClass().getSimpleName(), null);
				}
				ledger.settle(actionId, d.outcome(), d.reason(), d.reconciler());
				return d;
			});
			if (done != null) details = done.details();
		} catch (RpcException e) {
			if (phase.compareAndSet(NOT_STARTED, ABANDONED)) {
				ledger.settle(actionId, Outcome.NOT_APPLIED, e.code());
			} else {
				ledger.settleIf(actionId, Outcome.PENDING, Outcome.UNCERTAIN, e.code());
			}
		}
		emit(ledger.get(actionId));
		return result(ledger.get(actionId), details);
	}

	/** The answer to a supervised request: the ledger entry plus the effect's details. */
	public static JsonObject result(ActionLedger.Entry entry, JsonObject details) {
		JsonObject o = entryJson(entry);
		if (details != null) {
			for (String key : details.keySet()) o.add(key, details.get(key));
		}
		if (entry != null && entry.outcome().open()) {
			o.addProperty("note", "The outcome is not known yet: call supervised_reconcile with this actionId before anything else.");
		}
		return o;
	}

	public static JsonObject entryJson(ActionLedger.Entry entry) {
		JsonObject o = new JsonObject();
		if (entry == null) return o;
		o.addProperty("actionId", entry.id());
		o.addProperty("operation", entry.operation());
		o.addProperty("target", entry.target());
		o.addProperty("outcome", entry.outcome().id());
		if (entry.reason() != null) o.addProperty("reason", entry.reason());
		return o;
	}

	/** Reports a settled action on the event bus ({@code supervised_action}, see poll_events). */
	public static void emit(ActionLedger.Entry entry) {
		if (entry != null && McpFabric.events() != null) McpFabric.events().emit("supervised_action", entryJson(entry));
	}
}
