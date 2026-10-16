package dev.mcpfabric.supervision;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnedInputsTest {
	private final OwnedInputs<String> inputs = new OwnedInputs<>();
	private final List<String> cleaned = new ArrayList<>();

	private OwnedInputs.Cleanup release(String channel) {
		return () -> cleaned.add(channel);
	}

	@Test
	void drainReleasesOwnedChannelsInClaimOrder() {
		assertTrue(inputs.claim("forward", release("forward")));
		assertTrue(inputs.claim("jump", release("jump")));
		OwnedInputs.Drained<String> d = inputs.drain();
		assertEquals(List.of("forward", "jump"), d.released());
		assertEquals(List.of(), d.pending());
		assertEquals(List.of("forward", "jump"), cleaned);
		assertFalse(inputs.owns("forward"));
		assertEquals(List.of(), inputs.drain().released(), "a released channel is not cleaned again");
	}

	@Test
	void claimingAnOwnedChannelKeepsTheFirstCleanup() {
		assertTrue(inputs.claim("forward", release("first")));
		assertFalse(inputs.claim("forward", release("second")));
		inputs.drain();
		assertEquals(List.of("first"), cleaned);
	}

	@Test
	void aYieldedChannelIsNotCleaned() {
		inputs.claim("forward", release("forward"));
		inputs.claim("jump", release("jump"));
		assertTrue(inputs.yieldToPlayer("forward"));
		assertFalse(inputs.yieldToPlayer("forward"));
		inputs.drain();
		assertEquals(List.of("jump"), cleaned, "the player's key is left alone");
	}

	@Test
	void failedCleanupStaysPendingUntilItSucceeds() {
		int[] attempts = {0};
		inputs.claim("sneak", () -> ++attempts[0] >= 3);
		inputs.claim("sprint", () -> {
			throw new IllegalStateException("no player");
		});
		inputs.claim("forward", release("forward"));

		OwnedInputs.Drained<String> first = inputs.drain();
		assertEquals(List.of("forward"), first.released());
		assertEquals(List.of("sneak", "sprint"), first.pending());
		assertEquals(Set.of("sneak", "sprint"), inputs.pending());
		assertTrue(inputs.owns("sneak"));

		inputs.drain();
		OwnedInputs.Drained<String> third = inputs.drain();
		assertEquals(List.of("sneak"), third.released());
		assertEquals(Set.of("sprint"), inputs.pending());
		assertEquals(3, attempts[0]);

		assertTrue(inputs.yieldToPlayer("sprint"));
		assertEquals(Set.of(), inputs.pending());
	}

	@Test
	void cleanupCannotClaimYieldOrDrainWhileDraining() {
		List<RuntimeException> rejected = new ArrayList<>();
		inputs.claim("forward", () -> {
			for (Runnable r : new Runnable[] {
					() -> inputs.claim("jump", release("jump")),
					() -> inputs.yieldToPlayer("forward"),
					inputs::drain}) {
				try {
					r.run();
				} catch (IllegalStateException e) {
					rejected.add(e);
				}
			}
			cleaned.add("forward");
			return true;
		});
		OwnedInputs.Drained<String> d = inputs.drain();
		assertEquals(3, rejected.size());
		assertEquals(List.of("forward"), d.released());
		assertEquals(List.of("forward"), cleaned, "cleaned once");
		assertFalse(inputs.owns("jump"));
	}

	@Test
	void aRejectedReentrantDrainKeepsTheChannelPending() {
		int[] runs = {0};
		inputs.claim("forward", () -> {
			runs[0]++;
			inputs.drain();
			return true;
		});
		assertEquals(List.of("forward"), inputs.drain().pending());
		assertEquals(1, runs[0], "the nested drain did not run the cleanup again");
		assertTrue(inputs.owns("forward"));
	}

	@Test
	void drainingEndsEvenWhenACleanupThrowsAnError() {
		inputs.claim("forward", () -> {
			throw new AssertionError("boom");
		});
		assertThrows(AssertionError.class, inputs::drain);
		assertTrue(inputs.owns("forward"));
		assertTrue(inputs.yieldToPlayer("forward"), "the tracker is usable again");
	}
}
