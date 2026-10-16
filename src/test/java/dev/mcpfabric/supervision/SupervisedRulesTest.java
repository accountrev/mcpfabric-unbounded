package dev.mcpfabric.supervision;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupervisedRulesTest {
	@Test
	void blockIdsAreNamespacedAndPlain() {
		assertEquals("minecraft:stone", SupervisedRules.blockId("stone"));
		assertEquals("minecraft:stone", SupervisedRules.blockId(" Minecraft:Stone "));
		assertEquals("mymod:ore", SupervisedRules.blockId("mymod:ore"));
		for (String bad : new String[] {"", "stone[snowy=true]", "chest{Items:[]}", "a b", "stone;"}) {
			assertThrows(IllegalArgumentException.class, () -> SupervisedRules.blockId(bad), bad);
		}
	}

	@Test
	void blocksMayHaveStateButNoData() {
		assertEquals("minecraft:oak_stairs[facing=north,half=top]", SupervisedRules.block("oak_stairs[facing=north,half=top]"));
		assertEquals("minecraft:oak_stairs", SupervisedRules.baseId(SupervisedRules.block("oak_stairs[facing=north]")));
		for (String bad : new String[] {"command_block{Command:\"op x\"}", "stone[", "stone[facing]", "stone[a=b]{}", "stone 1"}) {
			assertThrows(IllegalArgumentException.class, () -> SupervisedRules.block(bad), bad);
		}
	}

	@Test
	void commandsNeedAnAllowedRoot() {
		List<String> allowed = List.of("time", "/weather");
		assertEquals("time set day", SupervisedRules.command(" /time set day ", allowed));
		assertEquals("WEATHER clear", SupervisedRules.command("WEATHER clear", allowed));
		assertThrows(IllegalArgumentException.class, () -> SupervisedRules.command("execute run time set day", allowed));
		assertThrows(IllegalArgumentException.class, () -> SupervisedRules.command("timeout", allowed));
		assertThrows(IllegalArgumentException.class, () -> SupervisedRules.command("time set day", List.of()));
		assertThrows(IllegalArgumentException.class, () -> SupervisedRules.command("time set day", null));
	}

	@Test
	void commandsAreOneLineWithoutData() {
		List<String> allowed = List.of("time", "give");
		assertThrows(IllegalArgumentException.class, () -> SupervisedRules.command("", allowed));
		assertThrows(IllegalArgumentException.class, () -> SupervisedRules.command("/", allowed));
		assertThrows(IllegalArgumentException.class, () -> SupervisedRules.command("time set day\nop me", allowed));
		assertThrows(IllegalArgumentException.class, () -> SupervisedRules.command("give @s stone{x:1}", allowed));
		assertThrows(IllegalArgumentException.class, () -> SupervisedRules.command("time " + "1".repeat(SupervisedRules.MAX_COMMAND_LENGTH), allowed));
	}
}
