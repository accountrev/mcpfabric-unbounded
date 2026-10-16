package dev.mcpfabric.supervision;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The input channels (keys, buttons) the automation holds, and how to give each one back (#27). It
 * is not wired into the mod yet.
 *
 * <p>Cleanup only touches channels the automation still owns: a channel the player has taken over is
 * {@linkplain #yieldToPlayer yielded} without running its cleanup. A cleanup that fails stays pending
 * and runs again on the next {@link #drain()}. A cleanup that claims, yields or drains while a drain
 * runs is rejected, so a callback can neither drop a channel from the books nor clean one twice.
 *
 * <p>Confined to the game thread, like the input state it tracks.
 */
public final class OwnedInputs<C> {
	/** Returns {@code true} once the channel is back at rest; {@code false} or a throw is a failure. */
	@FunctionalInterface
	public interface Cleanup {
		boolean run();
	}

	/** What a drain released, and what is still owned because its cleanup failed. */
	public record Drained<C>(List<C> released, List<C> pending) {}

	private final Map<C, Cleanup> owned = new LinkedHashMap<>();
	private final Set<C> failed = new LinkedHashSet<>();
	private boolean draining;

	/**
	 * Takes a channel for the automation. Returns {@code false} if it already owned the channel; the
	 * cleanup registered first is kept.
	 */
	public boolean claim(C channel, Cleanup cleanup) {
		Objects.requireNonNull(channel, "channel");
		Objects.requireNonNull(cleanup, "cleanup");
		checkNotDraining();
		return owned.putIfAbsent(channel, cleanup) == null;
	}

	/** The player took the channel over: forget it without running its cleanup. */
	public boolean yieldToPlayer(C channel) {
		checkNotDraining();
		failed.remove(channel);
		return owned.remove(channel) != null;
	}

	/** Runs the cleanup of every owned channel, in claim order. */
	public Drained<C> drain() {
		checkNotDraining();
		draining = true;
		List<C> released = new ArrayList<>();
		List<C> pending = new ArrayList<>();
		try {
			for (Iterator<Map.Entry<C, Cleanup>> it = owned.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry<C, Cleanup> e = it.next();
				boolean ok;
				try {
					ok = e.getValue().run();
				} catch (RuntimeException ex) {
					ok = false;
				}
				if (ok) {
					it.remove();
					failed.remove(e.getKey());
					released.add(e.getKey());
				} else {
					failed.add(e.getKey());
					pending.add(e.getKey());
				}
			}
		} finally {
			draining = false;
		}
		return new Drained<>(List.copyOf(released), List.copyOf(pending));
	}

	public boolean owns(C channel) {
		return owned.containsKey(channel);
	}

	/** Owned channels whose last cleanup failed. */
	public Set<C> pending() {
		return Set.copyOf(failed);
	}

	private void checkNotDraining() {
		if (draining) {
			throw new IllegalStateException("input cleanup is draining");
		}
	}
}
