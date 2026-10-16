package dev.mcpfabric.supervision;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The outcome of every supervised action (#27). An action is recorded as {@link Outcome#PENDING} when
 * its grant is issued and gets its final outcome from the effect. An action whose outcome is not
 * known ({@code pending} or {@code uncertain}) is open: no new action is granted until every open one
 * is reconciled, and an uncertain mutation is never retried automatically.
 *
 * <p>Open actions are kept until they are reconciled; of the closed ones only the latest
 * {@link #MAX_CLOSED} are kept. Thread-safe.
 */
public final class ActionLedger {
	public static final int MAX_CLOSED = 32;

	public enum Outcome {
		/** The effect happened and was read back. */
		APPLIED,
		/** Nothing was changed: refused at admission or a precondition did not hold. */
		NOT_APPLIED,
		/** An input sample ended early (STOP, the player, a context change); its keys were released. */
		INTERRUPTED,
		/** Still running, or started in the background (joining a world): reconcile it. */
		PENDING,
		/** The effect may or may not have happened: reconcile it before continuing. */
		UNCERTAIN,
		/** Reconciled without a readback: the caller accepted that the outcome is unknown. */
		ACKNOWLEDGED;

		public boolean open() {
			return this == PENDING || this == UNCERTAIN;
		}

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/** Reads the game again to settle an open action. Runs on a bridge thread. */
	@FunctionalInterface
	public interface Reconciler {
		Result check() throws Exception;
	}

	public record Result(Outcome outcome, String reason) {}

	public record Entry(String id, String operation, String target, Outcome outcome, String reason) {}

	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private final Map<String, Reconciler> reconcilers = new HashMap<>();
	private long next;

	/** Records a new pending action and returns its id. */
	public synchronized String open(String operation, String target) {
		String id = "a" + ++next;
		entries.put(id, new Entry(id, operation, target, Outcome.PENDING, null));
		return id;
	}

	/** Sets the outcome of an action; {@code reconciler} (may be null) settles it later if it stays open. */
	public synchronized void settle(String id, Outcome outcome, String reason, Reconciler reconciler) {
		Entry e = entries.get(id);
		if (e == null) return;
		entries.put(id, new Entry(id, e.operation(), e.target(), outcome, reason));
		if (outcome.open() && reconciler != null) {
			reconcilers.put(id, reconciler);
		} else if (!outcome.open()) {
			reconcilers.remove(id);
		}
		trim();
	}

	public void settle(String id, Outcome outcome, String reason) {
		settle(id, outcome, reason, null);
	}

	/** Sets the outcome only while the action still has {@code expected}; returns whether it did. */
	public synchronized boolean settleIf(String id, Outcome expected, Outcome outcome, String reason) {
		Entry e = entries.get(id);
		if (e == null || e.outcome() != expected) return false;
		settle(id, outcome, reason, null);
		return true;
	}

	public synchronized Entry get(String id) {
		return entries.get(id);
	}

	public synchronized Reconciler reconciler(String id) {
		return reconcilers.get(id);
	}

	/** Actions whose outcome is not known yet, oldest first. */
	public synchronized List<Entry> openEntries() {
		List<Entry> out = new ArrayList<>();
		for (Entry e : entries.values()) {
			if (e.outcome().open()) out.add(e);
		}
		return out;
	}

	/** The latest {@code limit} actions, oldest first. */
	public synchronized List<Entry> recent(int limit) {
		List<Entry> all = new ArrayList<>(entries.values());
		return List.copyOf(all.subList(Math.max(0, all.size() - limit), all.size()));
	}

	private void trim() {
		int closed = 0;
		for (Entry e : entries.values()) {
			if (!e.outcome().open()) closed++;
		}
		for (Iterator<Entry> it = entries.values().iterator(); it.hasNext() && closed > MAX_CLOSED; ) {
			Entry e = it.next();
			if (!e.outcome().open()) {
				it.remove();
				closed--;
			}
		}
	}
}
