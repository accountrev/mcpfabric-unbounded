package dev.mcpfabric.supervision;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupervisedAuthorityTest {
	private static final long TTL = 1_000;
	private static final Object T = "block 1 64 2";

	private final AtomicLong now = new AtomicLong(-500);
	private final SupervisedAuthority authority = new SupervisedAuthority(now::get);

	@Test
	void admitsOneActionAndOneInputSample() {
		authority.enterContext("world-a");
		SupervisedAuthority.Grant g = authority.grant(T, TTL);
		assertEquals(Admission.ADMITTED, authority.admitAction(g, T));
		assertEquals(Admission.USED, authority.admitAction(g, T));
		assertEquals(Admission.ADMITTED, authority.admitInputSample(g, Cardinal.FORWARD));
		assertEquals(Admission.USED, authority.admitInputSample(g, Cardinal.LEFT));
	}

	@Test
	void admitsOnlyTheTargetItWasGrantedFor() {
		authority.enterContext("world-a");
		SupervisedAuthority.Grant g = authority.grant(T, TTL);
		assertEquals(Admission.TARGET_MISMATCH, authority.admitAction(g, "block 1 64 3"));
		assertEquals(Admission.ADMITTED, authority.admitAction(g, T), "a mismatch does not use the grant");
		assertThrows(NullPointerException.class, () -> authority.grant(null, TTL));
	}

	@Test
	void inputSampleNeedsTheAction() {
		authority.enterContext("world-a");
		SupervisedAuthority.Grant g = authority.grant(T, TTL);
		assertEquals(Admission.NO_ACTION, authority.admitInputSample(g, Cardinal.BACK));
		assertEquals(Admission.ADMITTED, authority.admitAction(g, T));
		assertEquals(Admission.ADMITTED, authority.admitInputSample(g, Cardinal.BACK));
	}

	@Test
	void expiresAtTheDeadlineAndCannotBeRenewed() {
		authority.enterContext("world-a");
		SupervisedAuthority.Grant g = authority.grant(T, TTL);
		now.addAndGet(TTL - 1);
		assertEquals(Admission.ADMITTED, authority.admitAction(g, T));
		now.addAndGet(1);
		assertEquals(Admission.EXPIRED, authority.admitInputSample(g, Cardinal.RIGHT), "the sample is checked again");
		assertEquals(Admission.ADMITTED, authority.admitAction(authority.grant(T, TTL), T));
		assertEquals(Admission.EXPIRED, authority.admitInputSample(g, Cardinal.RIGHT), "a new grant does not revive the old one");
	}

	@Test
	void expiryIsSafeAcrossClockOverflow() {
		now.set(Long.MAX_VALUE - 10);
		authority.enterContext("world-a");
		SupervisedAuthority.Grant g = authority.grant(T, TTL);
		now.addAndGet(20);
		assertEquals(Admission.ADMITTED, authority.admitAction(g, T));
		now.addAndGet(TTL);
		assertEquals(Admission.EXPIRED, authority.admitInputSample(g, Cardinal.FORWARD));
	}

	@Test
	void stopFencesQueuedGrants() {
		authority.enterContext("world-a");
		SupervisedAuthority.Grant queued = authority.grant(T, TTL);
		SupervisedAuthority.Grant admitted = authority.grant(T, TTL);
		assertEquals(Admission.ADMITTED, authority.admitAction(admitted, T));
		authority.stop();
		assertEquals(Admission.STOPPED, authority.admitAction(queued, T));
		assertEquals(Admission.STOPPED, authority.admitInputSample(admitted, Cardinal.FORWARD));
		assertEquals(Admission.ADMITTED, authority.admitAction(authority.grant(T, TTL), T), "fresh intent works after STOP");
	}

	@Test
	void contextChangeFencesGrantsEvenWhenTheContextComesBack() {
		authority.enterContext("world-a");
		SupervisedAuthority.Grant g = authority.grant(T, TTL);
		authority.enterContext("world-b");
		assertEquals(Admission.CONTEXT_CHANGED, authority.admitAction(g, T));
		authority.enterContext("world-a");
		assertEquals(Admission.STOPPED, authority.admitAction(g, T));
	}

	@Test
	void reenteringTheSameContextKeepsGrants() {
		authority.enterContext("world-a");
		SupervisedAuthority.Grant g = authority.grant(T, TTL);
		authority.enterContext("world-a");
		assertEquals(Admission.ADMITTED, authority.admitAction(g, T));
	}

	@Test
	void leavingTheContextFencesGrantsAndStopsNewOnes() {
		authority.enterContext("world-a");
		SupervisedAuthority.Grant g = authority.grant(T, TTL);
		authority.enterContext(null);
		assertEquals(Admission.CONTEXT_CHANGED, authority.admitAction(g, T));
		assertThrows(IllegalStateException.class, () -> authority.grant(T, TTL));
	}

	@Test
	void ttlIsBounded() {
		authority.enterContext("world-a");
		assertThrows(IllegalArgumentException.class, () -> authority.grant(T, 0));
		assertThrows(IllegalArgumentException.class, () -> authority.grant(T, -1));
		assertThrows(IllegalArgumentException.class, () -> authority.grant(T, SupervisedAuthority.MAX_TTL_NANOS + 1));
		assertEquals(Admission.ADMITTED, authority.admitAction(authority.grant(T, SupervisedAuthority.MAX_TTL_NANOS), T));
	}

	@Test
	void refusesGrantsOfAnotherAuthority() {
		SupervisedAuthority other = new SupervisedAuthority(now::get);
		other.enterContext("world-a");
		authority.enterContext("world-a");
		SupervisedAuthority.Grant foreign = other.grant(T, TTL);
		assertThrows(IllegalArgumentException.class, () -> authority.admitAction(foreign, T));
	}

	@Test
	void fencingWinsOverUse() {
		authority.enterContext("world-a");
		SupervisedAuthority.Grant g = authority.grant(T, TTL);
		assertEquals(Admission.ADMITTED, authority.admitAction(g, T));
		authority.stop();
		assertEquals(Admission.STOPPED, authority.admitAction(g, T), "a fenced grant reports the fence, not its use");
	}
}
