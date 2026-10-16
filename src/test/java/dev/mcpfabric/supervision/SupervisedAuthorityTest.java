package dev.mcpfabric.supervision;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupervisedAuthorityTest {
	private static final long TTL = 1_000;

	private final AtomicLong now = new AtomicLong(-500);
	private final SupervisedAuthority authority = new SupervisedAuthority(now::get);

	@Test
	void admitsOneActionAndOneInputSample() {
		authority.enterContext("world-a");
		SupervisedAuthority.Grant g = authority.grant(TTL);
		assertEquals(Admission.ADMITTED, authority.admitAction(g));
		assertEquals(Admission.USED, authority.admitAction(g));
		assertEquals(Admission.ADMITTED, authority.admitInputSample(g, Cardinal.FORWARD));
		assertEquals(Admission.USED, authority.admitInputSample(g, Cardinal.LEFT));
	}

	@Test
	void inputSampleNeedsTheAction() {
		authority.enterContext("world-a");
		SupervisedAuthority.Grant g = authority.grant(TTL);
		assertEquals(Admission.NO_ACTION, authority.admitInputSample(g, Cardinal.BACK));
		assertEquals(Admission.ADMITTED, authority.admitAction(g));
		assertEquals(Admission.ADMITTED, authority.admitInputSample(g, Cardinal.BACK));
	}

	@Test
	void expiresAtTheDeadlineAndCannotBeRenewed() {
		authority.enterContext("world-a");
		SupervisedAuthority.Grant g = authority.grant(TTL);
		now.addAndGet(TTL - 1);
		assertEquals(Admission.ADMITTED, authority.admitAction(g));
		now.addAndGet(1);
		assertEquals(Admission.EXPIRED, authority.admitInputSample(g, Cardinal.RIGHT), "the sample is checked again");
		assertEquals(Admission.ADMITTED, authority.admitAction(authority.grant(TTL)));
		assertEquals(Admission.EXPIRED, authority.admitInputSample(g, Cardinal.RIGHT), "a new grant does not revive the old one");
	}

	@Test
	void expiryIsSafeAcrossClockOverflow() {
		now.set(Long.MAX_VALUE - 10);
		authority.enterContext("world-a");
		SupervisedAuthority.Grant g = authority.grant(TTL);
		now.addAndGet(20);
		assertEquals(Admission.ADMITTED, authority.admitAction(g));
		now.addAndGet(TTL);
		assertEquals(Admission.EXPIRED, authority.admitInputSample(g, Cardinal.FORWARD));
	}

	@Test
	void stopFencesQueuedGrants() {
		authority.enterContext("world-a");
		SupervisedAuthority.Grant queued = authority.grant(TTL);
		SupervisedAuthority.Grant admitted = authority.grant(TTL);
		assertEquals(Admission.ADMITTED, authority.admitAction(admitted));
		authority.stop();
		assertEquals(Admission.STOPPED, authority.admitAction(queued));
		assertEquals(Admission.STOPPED, authority.admitInputSample(admitted, Cardinal.FORWARD));
		assertEquals(Admission.ADMITTED, authority.admitAction(authority.grant(TTL)), "fresh intent works after STOP");
	}

	@Test
	void contextChangeFencesGrantsEvenWhenTheContextComesBack() {
		authority.enterContext("world-a");
		SupervisedAuthority.Grant g = authority.grant(TTL);
		authority.enterContext("world-b");
		assertEquals(Admission.CONTEXT_CHANGED, authority.admitAction(g));
		authority.enterContext("world-a");
		assertEquals(Admission.STOPPED, authority.admitAction(g));
	}

	@Test
	void reenteringTheSameContextKeepsGrants() {
		authority.enterContext("world-a");
		SupervisedAuthority.Grant g = authority.grant(TTL);
		authority.enterContext("world-a");
		assertEquals(Admission.ADMITTED, authority.admitAction(g));
	}

	@Test
	void leavingTheContextFencesGrantsAndStopsNewOnes() {
		authority.enterContext("world-a");
		SupervisedAuthority.Grant g = authority.grant(TTL);
		authority.enterContext(null);
		assertEquals(Admission.CONTEXT_CHANGED, authority.admitAction(g));
		assertThrows(IllegalStateException.class, () -> authority.grant(TTL));
	}

	@Test
	void ttlIsBounded() {
		authority.enterContext("world-a");
		assertThrows(IllegalArgumentException.class, () -> authority.grant(0));
		assertThrows(IllegalArgumentException.class, () -> authority.grant(-1));
		assertThrows(IllegalArgumentException.class, () -> authority.grant(SupervisedAuthority.MAX_TTL_NANOS + 1));
		assertEquals(Admission.ADMITTED, authority.admitAction(authority.grant(SupervisedAuthority.MAX_TTL_NANOS)));
	}

	@Test
	void refusesGrantsOfAnotherAuthority() {
		SupervisedAuthority other = new SupervisedAuthority(now::get);
		other.enterContext("world-a");
		authority.enterContext("world-a");
		SupervisedAuthority.Grant foreign = other.grant(TTL);
		assertThrows(IllegalArgumentException.class, () -> authority.admitAction(foreign));
	}

	@Test
	void fencingWinsOverUse() {
		authority.enterContext("world-a");
		SupervisedAuthority.Grant g = authority.grant(TTL);
		assertEquals(Admission.ADMITTED, authority.admitAction(g));
		authority.stop();
		assertEquals(Admission.STOPPED, authority.admitAction(g), "a fenced grant reports the fence, not its use");
	}
}
