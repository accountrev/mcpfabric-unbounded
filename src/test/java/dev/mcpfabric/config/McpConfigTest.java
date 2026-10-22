package dev.mcpfabric.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpConfigTest {
	@Test
	void itemComponentsAreSentByTheirToggle() {
		McpConfig.ItemComponents items = new McpConfig.ItemComponents();
		assertFalse(items.any(), "nothing is sent by default");

		items.enchantments = true;
		assertTrue(items.any());
		assertTrue(items.sends("minecraft:enchantments"));
		assertTrue(items.sends("minecraft:stored_enchantments"));
		assertTrue(items.sends("!minecraft:enchantments"), "a removed default component follows the same toggle");
		assertFalse(items.sends("minecraft:lore"));
		assertFalse(items.sends("minecraft:dyed_color"));

		items.other = true;
		assertTrue(items.sends("minecraft:dyed_color"), "components without a toggle of their own are other");
		assertTrue(items.sends("create:sequenced_assembly"));
		assertFalse(items.sends("minecraft:custom_data"), "custom data has its own toggle");
	}
}
