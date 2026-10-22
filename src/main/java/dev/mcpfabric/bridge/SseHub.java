package dev.mcpfabric.bridge;

import dev.mcpfabric.config.McpConfig;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Fan-out hub for Server-Sent Events. Each connected {@code GET /events} client owns a
 * {@link Subscriber} with a bounded queue; {@link #broadcast} offers serialized events to every
 * matching subscriber without blocking the emitting (game) thread.
 */
public final class SseHub {
	private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();
	/** Each open stream holds one bridge worker thread, so their number is capped (sseMaxSubscribers). */
	private final McpConfig.Limits limits;

	public SseHub(McpConfig.Limits limits) {
		this.limits = limits;
	}

	/** Registers a new stream, or returns null when {@code sseMaxSubscribers} are already open. */
	public synchronized Subscriber register(Set<String> typeFilter) {
		if (subscribers.size() >= limits.sseMaxSubscribers) return null;
		Subscriber s = new Subscriber(typeFilter, Math.max(1, limits.sseQueueSize));
		subscribers.add(s);
		return s;
	}

	public void unregister(Subscriber s) {
		subscribers.remove(s);
	}

	public int count() {
		return subscribers.size();
	}

	public void broadcast(String type, String json) {
		for (Subscriber s : subscribers) {
			if (s.accepts(type)) {
				s.queue.offer(json);
			}
		}
	}

	/** A single SSE connection's outbound queue. */
	public static final class Subscriber {
		private final Set<String> filter;
		private final BlockingQueue<String> queue;

		private Subscriber(Set<String> filter, int capacity) {
			this.filter = filter;
			this.queue = new LinkedBlockingQueue<>(capacity);
		}

		boolean accepts(String type) {
			return filter == null || filter.isEmpty() || filter.contains(type);
		}

		/** Wait up to timeoutMs for the next event, or null on timeout. */
		public String poll(long timeoutMs) throws InterruptedException {
			return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
		}
	}
}
