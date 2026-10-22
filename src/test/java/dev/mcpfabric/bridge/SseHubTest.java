package dev.mcpfabric.bridge;

import dev.mcpfabric.config.McpConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SseHubTest {
	@Test
	void capsTheNumberOfOpenStreams() {
		McpConfig.Limits limits = new McpConfig.Limits();
		SseHub hub = new SseHub(limits);
		List<SseHub.Subscriber> open = new ArrayList<>();
		for (int i = 0; i < limits.sseMaxSubscribers; i++) {
			SseHub.Subscriber s = hub.register(Set.of());
			assertNotNull(s);
			open.add(s);
		}
		assertNull(hub.register(Set.of()), "each stream holds a bridge thread, so the hub refuses more");

		hub.unregister(open.get(0));
		assertNotNull(hub.register(Set.of()), "a closed stream frees its place");
		assertEquals(limits.sseMaxSubscribers, hub.count());

		limits.sseMaxSubscribers = 20;
		assertNotNull(hub.register(Set.of()), "a raised limit applies without a restart");
	}
}
