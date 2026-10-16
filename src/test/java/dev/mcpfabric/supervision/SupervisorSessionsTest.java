package dev.mcpfabric.supervision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupervisorSessionsTest {
	private int fences;
	private final SupervisorSessions sessions = new SupervisorSessions(() -> fences++);

	@Test
	void acceptsGrowingSequenceNumbersAndRefusesReplays() {
		String id = sessions.open();
		assertTrue(id.matches("[0-9a-f]{32}"), id);
		assertEquals(SupervisorSessions.Check.OK, sessions.check(id, 1));
		assertEquals(SupervisorSessions.Check.OK, sessions.check(id, 5));
		assertEquals(SupervisorSessions.Check.REPLAYED, sessions.check(id, 5));
		assertEquals(SupervisorSessions.Check.REPLAYED, sessions.check(id, 2));
		assertEquals(SupervisorSessions.Check.OK, sessions.check(id, 6));
	}

	@Test
	void aNewSessionTakesOverAndFences() {
		String first = sessions.open();
		String second = sessions.open();
		assertNotEquals(first, second);
		assertEquals(2, fences);
		assertEquals(SupervisorSessions.Check.STALE_SESSION, sessions.check(first, 1));
		assertEquals(SupervisorSessions.Check.OK, sessions.check(second, 1), "sequence numbers start over");
	}

	@Test
	void closingEndsTheSessionOnlyWithItsId() {
		String id = sessions.open();
		assertFalse(sessions.close("0".repeat(32)));
		assertTrue(sessions.active());
		assertTrue(sessions.close(id));
		assertEquals(2, fences);
		assertFalse(sessions.active());
		assertEquals(SupervisorSessions.Check.STALE_SESSION, sessions.check(id, 1));
		assertFalse(sessions.close(id));
	}

	@Test
	void aRequestWithoutSessionIsRefused() {
		assertEquals(SupervisorSessions.Check.NO_SESSION, sessions.check(null, 1));
		sessions.open();
		assertEquals(SupervisorSessions.Check.NO_SESSION, sessions.check("", 1));
		assertEquals(SupervisorSessions.Check.STALE_SESSION, sessions.check("not-the-session", 1));
	}
}
