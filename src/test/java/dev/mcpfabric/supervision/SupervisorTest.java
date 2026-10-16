package dev.mcpfabric.supervision;

import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.supervision.ActionLedger.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupervisorTest {
	private final AtomicLong now = new AtomicLong();
	private final Supervisor sv = new Supervisor(new SupervisedAuthority(now::get));
	private String session;
	private int stops;

	@BeforeEach
	void setUp() {
		sv.operation("read_block", () -> null);
		sv.operation("command", () -> "needs an integrated or dedicated server");
		sv.onStop(() -> stops++);
		sv.enterContext("sp:test|minecraft:overworld");
		session = sv.sessions().open();
	}

	private String code(ThrowingRunnable r) {
		return assertThrows(RpcException.class, r::run).code();
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	@Test
	void grantsARequestAndRecordsItAsPending() throws RpcException {
		Supervisor.Request req = sv.request(session, 1, "read_block", "pos", "0 64 0");
		assertEquals(Outcome.PENDING, sv.ledger().get(req.actionId()).outcome());
		assertEquals(Admission.ADMITTED, sv.authority().admitAction(req.grant(), "pos"));
	}

	@Test
	void refusesRequestsOfOtherSessionsAndReplays() throws RpcException {
		assertEquals("no_session", code(() -> sv.request(null, 1, "read_block", "pos", "t")));
		assertEquals("stale_session", code(() -> sv.request("f".repeat(32), 1, "read_block", "pos", "t")));
		Supervisor.Request req = sv.request(session, 7, "read_block", "pos", "t");
		sv.ledger().settle(req.actionId(), Outcome.APPLIED, null);
		assertEquals("replayed", code(() -> sv.request(session, 7, "read_block", "pos", "t")));
	}

	@Test
	void anOpenActionBlocksTheNextUntilReconciled() throws RpcException {
		Supervisor.Request req = sv.request(session, 1, "read_block", "pos", "t");
		assertEquals("unreconciled", code(() -> sv.request(session, 2, "read_block", "pos", "t")));
		sv.ledger().settle(req.actionId(), Outcome.UNCERTAIN, "timeout");
		assertEquals("unreconciled", code(() -> sv.request(session, 3, "read_block", "pos", "t")));
		sv.ledger().settle(req.actionId(), Outcome.ACKNOWLEDGED, "accepted");
		sv.request(session, 4, "read_block", "pos", "t");
	}

	@Test
	void reportsOperationsThatCannotRun() {
		assertEquals("not_available", code(() -> sv.request(session, 1, "command", "time set day", "time set day")));
		assertEquals("not_available", code(() -> sv.request(session, 2, "input", "FORWARD", "forward")));
		assertEquals("needs an integrated or dedicated server", sv.unavailable("command"));
		assertEquals("not implemented on this side of the bridge", sv.unavailable("enter_world"));
		assertThrows(IllegalArgumentException.class, () -> sv.operation("fly", () -> null));
	}

	@Test
	void stopAndNewSessionsFenceIssuedGrants() throws RpcException {
		Supervisor.Request req = sv.request(session, 1, "read_block", "pos", "t");
		int before = stops;
		sv.stop();
		assertEquals(before + 1, stops);
		assertEquals(Admission.STOPPED, sv.authority().admitAction(req.grant(), "pos"));
		sv.ledger().settle(req.actionId(), Outcome.NOT_APPLIED, "stopped");

		Supervisor.Request next = sv.request(session, 2, "read_block", "pos", "t");
		sv.sessions().open();
		assertEquals(Admission.STOPPED, sv.authority().admitAction(next.grant(), "pos"), "a new session fences the old grants");
	}

	@Test
	void grantsExpireAfterTheTtl() throws RpcException {
		Supervisor.Request req = sv.request(session, 1, "read_block", "pos", "t");
		now.addAndGet(Supervisor.GRANT_TTL_NANOS);
		assertEquals(Admission.EXPIRED, sv.authority().admitAction(req.grant(), "pos"));
	}

	@Test
	void needsAContext() {
		sv.enterContext(null);
		assertEquals("no_context", code(() -> sv.request(session, 1, "read_block", "pos", "t")));
	}
}
