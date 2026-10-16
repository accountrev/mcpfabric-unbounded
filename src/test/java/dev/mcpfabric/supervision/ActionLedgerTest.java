package dev.mcpfabric.supervision;

import dev.mcpfabric.supervision.ActionLedger.Outcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionLedgerTest {
	private final ActionLedger ledger = new ActionLedger();

	@Test
	void anActionIsOpenUntilItHasAKnownOutcome() {
		String id = ledger.open("set_block", "minecraft:overworld 0 64 0");
		assertEquals(Outcome.PENDING, ledger.get(id).outcome());
		assertEquals(List.of(ledger.get(id)), ledger.openEntries());
		ledger.settle(id, Outcome.APPLIED, null);
		assertEquals(List.of(), ledger.openEntries());
		assertEquals("applied", ledger.get(id).outcome().id());
	}

	@Test
	void anUncertainActionKeepsItsReconcilerUntilSettled() {
		String id = ledger.open("set_block", "t");
		ActionLedger.Reconciler r = () -> new ActionLedger.Result(Outcome.APPLIED, "read back");
		ledger.settle(id, Outcome.UNCERTAIN, "readback_mismatch", r);
		assertNotNull(ledger.reconciler(id));
		assertTrue(ledger.get(id).outcome().open());
		ledger.settle(id, Outcome.APPLIED, "read back");
		assertNull(ledger.reconciler(id));
	}

	@Test
	void settleIfOnlyChangesTheExpectedOutcome() {
		String id = ledger.open("input", "forward for 4 ticks");
		ledger.settle(id, Outcome.APPLIED, null);
		assertFalse(ledger.settleIf(id, Outcome.PENDING, Outcome.UNCERTAIN, "timeout"), "a late timeout does not override the result");
		assertEquals(Outcome.APPLIED, ledger.get(id).outcome());

		String late = ledger.open("input", "back for 2 ticks");
		assertTrue(ledger.settleIf(late, Outcome.PENDING, Outcome.UNCERTAIN, "timeout"));
		ledger.settle(late, Outcome.INTERRUPTED, "stopped");
		assertEquals(Outcome.INTERRUPTED, ledger.get(late).outcome(), "the effect's own result settles it");
	}

	@Test
	void keepsOpenActionsAndOnlyTheLatestClosedOnes() {
		String open = ledger.open("command", "time set day");
		ledger.settle(open, Outcome.UNCERTAIN, "timeout");
		String last = null;
		for (int i = 0; i < ActionLedger.MAX_CLOSED + 10; i++) {
			last = ledger.open("read_block", "t" + i);
			ledger.settle(last, Outcome.APPLIED, null);
		}
		assertNotNull(ledger.get(open), "an open action is never dropped");
		assertNotNull(ledger.get(last));
		assertNull(ledger.get("a2"), "the oldest closed actions are");
		assertEquals(ActionLedger.MAX_CLOSED + 1, ledger.recent(1000).size());
		assertEquals(List.of(ledger.get(last)), ledger.recent(1));
	}

	@Test
	void openOutcomes() {
		for (Outcome o : Outcome.values()) {
			assertEquals(o == Outcome.PENDING || o == Outcome.UNCERTAIN, o.open(), o.name());
		}
	}
}
