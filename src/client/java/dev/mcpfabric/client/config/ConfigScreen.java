package dev.mcpfabric.client.config;

import dev.mcpfabric.McpFabric;
import dev.mcpfabric.config.McpConfig;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.gui.entries.BooleanListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The config screen: the limits, the MCP server's settings and the extra-data toggles of
 * mcpfabric.config.json. Built with Cloth Config, an optional dependency, so only call it when Cloth
 * Config is loaded; Mod Menu opens it on Fabric, the mod list on NeoForge. Saved values apply at once
 * (the MCP server reads its settings within about 15 seconds).
 */
public final class ConfigScreen {
	private ConfigScreen() {}

	public static Screen create(Screen parent) {
		McpConfig cfg = McpFabric.config();
		McpConfig.Limits l = cfg.limits, dl = new McpConfig.Limits();
		McpConfig.McpServer m = cfg.mcpServer, dm = new McpConfig.McpServer();
		McpConfig.Data d = cfg.data;
		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.literal("MCP Fabric"))
				.setSavingRunnable(cfg::save);
		ConfigEntryBuilder entries = builder.entryBuilder();

		McpConfig dc = new McpConfig();
		Tab general = new Tab(builder.getOrCreateCategory(Component.literal("General")), entries);
		general.tab.addEntry(entries.startTextField(Component.literal("Host"), cfg.host)
				.setDefaultValue(dc.host)
				.setErrorSupplier(v -> v.isBlank() ? Optional.of(Component.literal("Enter an address")) : Optional.empty())
				.setTooltip(lines("Address the bridge listens on. Keep 127.0.0.1: whoever reaches the bridge\ngets operator-level control of the game. Needs a restart.\nDefault: " + dc.host))
				.setSaveConsumer(v -> cfg.host = v.strip())
				.requireRestart()
				.build());
		general.tab.addEntry(entries.startIntField(Component.literal("Port"), cfg.port)
				.setDefaultValue(dc.port)
				.setMin(1)
				.setMax(65535)
				.setTooltip(lines("Port the bridge listens on; the MCP server's MCPFABRIC_URL must use it.\nNeeds a restart.\nDefault: " + dc.port))
				.setSaveConsumer(v -> cfg.port = v)
				.requireRestart()
				.build());
		// The token is a secret, so it is copied, never shown.
		general.tab.addEntry(new ButtonRowEntry(Button.builder(Component.literal("Copy token (for MCPFABRIC_TOKEN)"), b -> {
			Minecraft.getInstance().keyboardHandler.setClipboard(cfg.token);
			b.setMessage(Component.literal("Token copied"));
		}).build()));
		general.tab.addEntry(general.toggle("Require the token", cfg.requireAuth, dc.requireAuth, v -> cfg.requireAuth = v,
				"Every request needs the bearer token. Off, any program on this machine can control the game."));
		general.tab.addEntry(general.toggle("World writes", cfg.enableWorldWrite, dc.enableWorldWrite, v -> cfg.enableWorldWrite = v,
				"set_block, fill_blocks, set_time, set_weather, summon_entity, remove_entity."));
		general.tab.addEntry(general.toggle("Commands", cfg.enableCommands, dc.enableCommands, v -> cfg.enableCommands = v,
				"run_command and the player admin tools (teleport, give, game mode, kick...)."));
		general.tab.addEntry(general.toggle("Player control", cfg.enablePlayerControl, dc.enablePlayerControl, v -> cfg.enablePlayerControl = v,
				"Moving, looking, mining, placing, inventory, containers, crafting, navigation,\nleaving and joining worlds."));
		general.tab.addEntry(general.toggle("Vision", cfg.enableVision, dc.enableVision, v -> cfg.enableVision = v,
				"screenshot, describe_scene and perception, which sees through walls."));
		general.tab.addEntry(general.toggle("UI control", cfg.enableUiControl, dc.enableUiControl, v -> cfg.enableUiControl = v,
				"Clicking buttons and typing in game screens (get_ui, click_widget, set_text_field).\nAlso needs player control."));
		general.tab.addEntry(general.toggle("Supervised control", cfg.enableSupervisedControl, dc.enableSupervisedControl, v -> cfg.enableSupervisedControl = v,
				"One narrow action per call under short-lived authority. Independent of the flags above."));
		general.tab.addEntry(entries.startStrList(Component.literal("Supervised worlds"), cfg.supervisedWorlds)
				.setDefaultValue(dc.supervisedWorlds)
				.setTooltip(lines("Save folders supervised_enter_world may open (disposable worlds)."))
				.setSaveConsumer(v -> cfg.supervisedWorlds = new ArrayList<>(v))
				.build());
		general.tab.addEntry(entries.startStrList(Component.literal("Supervised commands"), cfg.supervisedCommands)
				.setDefaultValue(dc.supervisedCommands)
				.setTooltip(lines("Command roots supervised_command may run. Data ({...}) is refused in every command."))
				.setSaveConsumer(v -> cfg.supervisedCommands = new ArrayList<>(v))
				.build());

		Tab perception = new Tab(builder.getOrCreateCategory(Component.literal("Perception & vision")), entries);
		perception.number("Nearby entities", l.nearbyEntitiesMax, dl.nearbyEntitiesMax, 0, v -> l.nearbyEntitiesMax = v,
				"Most entities perception.entities returns (agent combat and item pickup).");
		perception.number("Nearby entities radius", l.nearbyEntitiesRadius, dl.nearbyEntitiesRadius, 1, v -> l.nearbyEntitiesRadius = v,
				"Largest radius in blocks perception.entities searches.");
		perception.number("Scene entities", l.sceneEntitiesMax, dl.sceneEntitiesMax, 0, v -> l.sceneEntitiesMax = v,
				"Most visible entities describe_scene lists.");
		perception.number("Scene distance", l.sceneMaxDistance, dl.sceneMaxDistance, 1, v -> l.sceneMaxDistance = v,
				"Largest maxDistance in blocks describe_scene accepts.");
		perception.number("Scene rays", l.sceneMaxRays, dl.sceneMaxRays, 1, v -> l.sceneMaxRays = v,
				"Largest rayColumns and rayRows describe_scene accepts.");
		perception.tab.addEntry(entries.startEnumSelector(Component.literal("Scan radius mode"), McpConfig.RadiusMode.class, l.scanRadiusMode)
				.setDefaultValue(dl.scanRadiusMode)
				.setEnumNameProvider(mode -> Component.literal(mode == McpConfig.RadiusMode.PERCENT ? "% of render distance" : "Chunks"))
				.setTooltip(lines("How the observe scan radius is capped: a fixed number of chunks,\nor a share of your render distance that follows it when it changes."))
				.setSaveConsumer(v -> l.scanRadiusMode = v)
				.build());
		perception.number("Scan radius (chunks)", l.scanRadiusChunks, dl.scanRadiusChunks, 0, v -> l.scanRadiusChunks = v,
				"Largest observe scan radius in Chunks mode. Only loaded chunks are scanned.\nThe scan runs on the render thread: large values can make the game stutter.");
		perception.tab.addEntry(entries.startIntField(Component.literal("Scan radius (% of render distance)"), l.scanRadiusPercent)
				.setDefaultValue(dl.scanRadiusPercent)
				.setMin(0)
				.setMax(100)
				.setTooltip(lines("Largest observe scan radius in % mode, rounded down.\nYour render distance is "
						+ Minecraft.getInstance().options.getEffectiveRenderDistance() + " chunks now.\nDefault: " + dl.scanRadiusPercent))
				.setSaveConsumer(v -> l.scanRadiusPercent = v)
				.build());
		perception.number("Scan points of interest", l.scanMaxPois, dl.scanMaxPois, 0, v -> l.scanMaxPois = v,
				"Most containers, workstations, beds and portals one observe scan reports.");
		perception.number("Scan block matches", l.scanFindLimitMax, dl.scanFindLimitMax, 1, v -> l.scanFindLimitMax = v,
				"Most block matches one scan may be asked for.");
		perception.number("Block probes", l.blockProbesMax, dl.blockProbesMax, 1, v -> l.blockProbesMax = v,
				"Most positions one perception.blocks call checks.");
		perception.number("UI widgets", l.uiMaxWidgets, dl.uiMaxWidgets, 0, v -> l.uiMaxWidgets = v,
				"Most widgets get_ui lists.");
		perception.number("UI text length", l.uiMaxText, dl.uiMaxText, 0, v -> l.uiMaxText = v,
				"Longest widget text get_ui returns and set_text_field accepts.");
		perception.number("Recipe alternatives", l.recipeMaxAlternatives, dl.recipeMaxAlternatives, 1, v -> l.recipeMaxAlternatives = v,
				"Most accepted items listed per recipe slot.");
		perception.number("Path search budget", l.navNodeBudget, dl.navNodeBudget, 1, v -> l.navNodeBudget = v,
				"Nodes navigate_to may explore to find a path. Runs on the render thread.");

		Tab world = new Tab(builder.getOrCreateCategory(Component.literal("World & entities")), entries);
		world.number("Region volume", l.regionMaxVolume, dl.regionMaxVolume, 1, v -> l.regionMaxVolume = v,
				"Most positions get_blocks_region may visit (default 256x256x256).\nThe read runs on the server thread: large values can freeze the server.");
		world.number("Region blocks (default)", l.regionDefaultMaxBlocks, dl.regionDefaultMaxBlocks, 1, v -> l.regionDefaultMaxBlocks = v,
				"Blocks get_blocks_region returns when maxBlocks is not given.");
		world.number("Region blocks (max)", l.regionMaxBlocks, dl.regionMaxBlocks, 1, v -> l.regionMaxBlocks = v,
				"Largest maxBlocks get_blocks_region accepts.");
		world.number("Block search budget", l.findBlocksScanBudget, dl.findBlocksScanBudget, 0, v -> l.findBlocksScanBudget = v,
				"Positions find_blocks visits before it stops. Runs on the server thread.");
		world.number("Block search radius", l.findBlocksMaxRadius, dl.findBlocksMaxRadius, 1, v -> l.findBlocksMaxRadius = v,
				"Largest radius in blocks find_blocks accepts.");
		world.number("Block search results", l.findBlocksMaxResults, dl.findBlocksMaxResults, 1, v -> l.findBlocksMaxResults = v,
				"Largest maxResults find_blocks accepts.");
		world.number("Entity query radius", l.entitiesQueryMaxRadius, dl.entitiesQueryMaxRadius, 1, v -> l.entitiesQueryMaxRadius = v,
				"Largest radius in blocks query_entities accepts.");
		world.number("Entity query results", l.entitiesQueryMaxResults, dl.entitiesQueryMaxResults, 1, v -> l.entitiesQueryMaxResults = v,
				"Largest maxResults query_entities accepts.");
		world.number("Raycast distance", l.raycastMaxDistance, dl.raycastMaxDistance, 1, v -> l.raycastMaxDistance = v,
				"Largest maxDistance in blocks raycast accepts.");
		world.number("Give item count", l.giveItemMaxCount, dl.giveItemMaxCount, 1, v -> l.giveItemMaxCount = v,
				"Largest count give_item accepts.");

		Tab events = new Tab(builder.getOrCreateCategory(Component.literal("Events & bridge")), entries);
		events.number("Event buffer", l.eventBufferSize, dl.eventBufferSize, 0, v -> l.eventBufferSize = v,
				"Recent events kept for poll_events and get_recent_chat.");
		events.number("Events per call", l.eventsMaxLimit, dl.eventsMaxLimit, 1, v -> l.eventsMaxLimit = v,
				"Largest limit poll_events and get_recent_chat accept.");
		events.number("Event stream queue", l.sseQueueSize, dl.sseQueueSize, 1, v -> l.sseQueueSize = v,
				"Events one /events stream may have waiting; applies to new streams.");
		events.number("Event streams", l.sseMaxSubscribers, dl.sseMaxSubscribers, 0, v -> l.sseMaxSubscribers = v,
				"Most /events streams open at once. Each holds a bridge thread.");
		events.number("Request size (bytes)", l.maxBodyBytes, dl.maxBodyBytes, 1024, v -> l.maxBodyBytes = v,
				"Largest request body the bridge reads.");

		Tab agent = new Tab(builder.getOrCreateCategory(Component.literal("Agent runtime")), entries);
		agent.number("Item types shown", m.itemTypesShown, dm.itemTypesShown, 0, v -> m.itemTypesShown = v,
				"Item types listed in agent text (explore, collect_blocks, plan_craft) before \"+N more\".");
		agent.number("Brief item types", m.briefItemTypesShown, dm.briefItemTypesShown, 0, v -> m.briefItemTypesShown = v,
				"Inventory item types agent_brief lists.");
		agent.number("Container item types", m.containerItemTypesShown, dm.containerItemTypesShown, 0, v -> m.containerItemTypesShown = v,
				"Item types listed for a container (open_container, container_transfer, its memory).");
		agent.number("Observe matches asked", m.observeFindLimit, dm.observeFindLimit, 1, v -> m.observeFindLimit = v,
				"Block matches observe asks the game for when you pass find.");
		agent.number("Observe matches shown", m.observeFoundShown, dm.observeFoundShown, 0, v -> m.observeFoundShown = v,
				"Block matches observe lists.");
		agent.number("Observe new places", m.observeNewPlacesShown, dm.observeNewPlacesShown, 0, v -> m.observeNewPlacesShown = v,
				"New places observe lists.");
		agent.number("Observe ore types", m.observeOreTypesShown, dm.observeOreTypesShown, 0, v -> m.observeOreTypesShown = v,
				"Ore types observe lists.");
		agent.number("Explore new places", m.exploreNewPlacesShown, dm.exploreNewPlacesShown, 0, v -> m.exploreNewPlacesShown = v,
				"New places explore lists when it ends.");
		agent.number("Recipes shown", m.recipesShown, dm.recipesShown, 0, v -> m.recipesShown = v,
				"Recipes get_recipes lists.");
		agent.number("Recipe alternatives shown", m.recipeAlternativesShown, dm.recipeAlternativesShown, 1, v -> m.recipeAlternativesShown = v,
				"Accepted items get_recipes lists per slot.");
		agent.number("plan_craft containers checked", m.planCraftStoresRecalled, dm.planCraftStoresRecalled, 0, v -> m.planCraftStoresRecalled = v,
				"Remembered containers plan_craft looks through for missing items.");
		agent.number("plan_craft containers shown", m.planCraftStoresShown, dm.planCraftStoresShown, 0, v -> m.planCraftStoresShown = v,
				"Containers plan_craft lists.");
		agent.number("Recall results", m.recallMaxLimit, dm.recallMaxLimit, 1, v -> m.recallMaxLimit = v,
				"Largest limit recall accepts.");
		agent.number("Recall chunk counts", m.recallResourceChunks, dm.recallResourceChunks, 0, v -> m.recallResourceChunks = v,
				"Scanned chunks recall lists for a resource.");
		agent.number("Memory text length", m.memoryBodyChars, dm.memoryBodyChars, 0, v -> m.memoryBodyChars = v,
				"Characters of a memory's details shown.");
		agent.number("Brief memory text length", m.briefMemoryBodyChars, dm.briefMemoryBodyChars, 0, v -> m.briefMemoryBodyChars = v,
				"Characters of a memory's details agent_brief shows.");
		agent.number("Brief nearby memories", m.briefNearbyMemories, dm.briefNearbyMemories, 0, v -> m.briefNearbyMemories = v,
				"Nearby memories agent_brief lists.");
		agent.number("Brief map radius", m.briefMapRadius, dm.briefMapRadius, 0, v -> m.briefMapRadius = v,
				"Radius in chunks of agent_brief's map.");
		agent.number("Map view radius", m.mapViewMaxRadius, dm.mapViewMaxRadius, 1, v -> m.mapViewMaxRadius = v,
				"Largest radius in chunks map_view accepts.");
		agent.number("Job log lines kept", m.jobLogLines, dm.jobLogLines, 0, v -> m.jobLogLines = v,
				"Log lines a job keeps.");
		agent.number("Job log lines shown", m.jobLogShown, dm.jobLogShown, 0, v -> m.jobLogShown = v,
				"Log lines job_status shows.");
		agent.number("Job history", m.jobHistory, dm.jobHistory, 0, v -> m.jobHistory = v,
				"Finished jobs kept for job_status.");
		agent.number("collect_blocks count", m.collectBlocksMaxCount, dm.collectBlocksMaxCount, 1, v -> m.collectBlocksMaxCount = v,
				"Largest count collect_blocks accepts.");
		agent.number("collect_blocks radius", m.collectBlocksMaxRadius, dm.collectBlocksMaxRadius, 8, v -> m.collectBlocksMaxRadius = v,
				"Largest radius in blocks collect_blocks accepts.");
		agent.number("collect_blocks seconds", m.collectBlocksMaxSeconds, dm.collectBlocksMaxSeconds, 10, v -> m.collectBlocksMaxSeconds = v,
				"Largest maxSeconds collect_blocks accepts.");
		agent.number("craft_item count", m.craftItemMaxCount, dm.craftItemMaxCount, 1, v -> m.craftItemMaxCount = v,
				"Largest count craft_item accepts.");
		agent.number("plan_craft count", m.planCraftMaxCount, dm.planCraftMaxCount, 1, v -> m.planCraftMaxCount = v,
				"Largest count plan_craft accepts.");

		Tab time = new Tab(builder.getOrCreateCategory(Component.literal("Time limits")), entries);
		time.number("Game call timeout (ms)", cfg.callTimeoutMs, dc.callTimeoutMs, 100, v -> cfg.callTimeoutMs = v,
				"How long one call may wait for the game thread.");
		time.number("Bridge timeout (ms)", m.bridgeTimeoutMs, dm.bridgeTimeoutMs, 100, v -> m.bridgeTimeoutMs = v,
				"How long the MCP server waits for the game.\nMCPFABRIC_TIMEOUT_MS overrides it.");
		time.number("Job time limit (minutes)", m.jobMaxMinutes, dm.jobMaxMinutes, 1, v -> m.jobMaxMinutes = v,
				"Background jobs (travel_to, explore, collect_blocks, craft_item) stop after this.");
		time.number("HTTP session idle (minutes)", m.httpSessionIdleMinutes, dm.httpSessionIdleMinutes, 1, v -> m.httpSessionIdleMinutes = v,
				"Idle MCP sessions are closed after this (MCPFABRIC_TRANSPORT=http only).");
		time.number("HTTP sessions", m.maxHttpSessions, dm.maxHttpSessions, 1, v -> m.maxHttpSessions = v,
				"Most MCP sessions open at once (MCPFABRIC_TRANSPORT=http only).");

		Tab data = new Tab(builder.getOrCreateCategory(Component.literal("Extra data")), entries);
		McpConfig.ItemComponents items = d.items;
		data.group("Item components", "Data components of items: inventory, equipment, containers,\nentity equipment and dropped items.",
				data.toggle("Enchantments", items.enchantments, v -> items.enchantments = v, "Enchantments, and the stored ones of enchanted books."),
				data.toggle("Names", items.names, v -> items.names = v, "Custom (anvil) names and item names."),
				data.toggle("Lore", items.lore, v -> items.lore = v, "Lore lines."),
				data.toggle("Durability", items.durability, v -> items.durability = v, "Damage, maximum damage and unbreakable."),
				data.toggle("Potions", items.potions, v -> items.potions = v, "Potion and suspicious stew effects."),
				data.toggle("Attribute modifiers", items.attributeModifiers, v -> items.attributeModifiers = v, "Attribute modifiers of the item."),
				data.toggle("Custom data", items.customData, v -> items.customData = v, "Data mods and commands store on the item."),
				data.toggle("Other components", items.other, v -> items.other = v, "Every other component: dyes, trims, banners, contents of shulker boxes and bundles..."));
		McpConfig.EntityDetails entities = d.entities;
		data.group("Entity details", "Added to every query_entities entry; get_entity already has\nmotion and equipment.",
				data.toggle("Motion and rotation", entities.motion, v -> entities.motion = v, "Velocity, yaw, pitch and whether it is on the ground."),
				data.toggle("Equipment", entities.equipment, v -> entities.equipment = v, "Held items and armor."),
				data.toggle("Every attribute", entities.attributes, v -> entities.attributes = v, "All attributes by id, also in get_entity instead of its five."),
				data.toggle("Effects", entities.effects, v -> entities.effects = v, "Active effects, also in get_entity."),
				data.toggle("Dropped item", entities.item, v -> entities.item = v, "The stack a dropped item entity holds, also in get_entity."));
		McpConfig.EntityNbt nbt = d.entityNbt;
		data.group("Entity NBT", "query_entities and get_entity add each entity's full saved data.\nNeeds the world to run here: singleplayer, LAN host or a server.",
				data.toggle("Players", nbt.players, v -> nbt.players = v, "Includes their inventory and ender chest."),
				data.toggle("Other entities", nbt.others, v -> nbt.others = v, "Mobs, items, vehicles..."));
		McpConfig.BlockStates states = d.blockStates;
		data.group("Block states", "Block state properties (facing, waterlogged...). get_block always has them.",
				data.toggle("In region reads", states.regions, v -> states.regions = v, "get_blocks_region"),
				data.toggle("In block searches", states.searches, v -> states.searches = v, "find_blocks"));
		McpConfig.BlockEntities blockEntities = d.blockEntities;
		data.group("Block entity data", "get_block, get_blocks_region and find_blocks add block entity data.\nNeeds the world to run here.",
				data.toggle("Containers", blockEntities.containers, v -> blockEntities.containers = v, "Chests, barrels, furnaces, hoppers...: their items."),
				data.toggle("Signs", blockEntities.signs, v -> blockEntities.signs = v, "Sign and hanging sign text."),
				data.toggle("Spawners", blockEntities.spawners, v -> blockEntities.spawners = v, "Spawners and trial spawners: what they spawn."),
				data.toggle("Other block entities", blockEntities.other, v -> blockEntities.other = v, "Beds, banners, lecterns, beehives, command blocks..."));

		return builder.build();
	}

	/** Tooltip text, one line per {@code \n}. */
	private static Component[] lines(String text) {
		return Arrays.stream(text.split("\n")).map(Component::literal).toArray(Component[]::new);
	}

	/** One tab; every number field takes whole numbers only, and every field has a reset button. */
	private record Tab(ConfigCategory tab, ConfigEntryBuilder entries) {
		void number(String name, int value, int def, int min, Consumer<Integer> save, String tooltip) {
			tab.addEntry(entries.startIntField(Component.literal(name), value)
					.setDefaultValue(def)
					.setMin(min)
					.setTooltip(lines(tooltip + "\nDefault: " + def))
					.setSaveConsumer(save)
					.build());
		}

		BooleanListEntry toggle(String name, boolean value, Consumer<Boolean> save, String tooltip) {
			return toggle(name, value, false, save, tooltip);
		}

		BooleanListEntry toggle(String name, boolean value, boolean def, Consumer<Boolean> save, String tooltip) {
			return entries.startBooleanToggle(Component.literal(name), value)
					.setDefaultValue(def)
					.setTooltip(lines(tooltip + "\nDefault: " + (def ? "on" : "off")))
					.setSaveConsumer(save)
					.build();
		}

		/** A collapsed group of toggles, led by buttons that turn them all on or off. */
		void group(String name, String tooltip, BooleanListEntry... toggles) {
			List<AbstractConfigListEntry> list = new ArrayList<>();
			list.add(new ButtonRowEntry(
					Button.builder(Component.literal("Enable all"), b -> setAll(toggles, true)).build(),
					Button.builder(Component.literal("Disable all"), b -> setAll(toggles, false)).build()));
			list.addAll(List.of(toggles));
			tab.addEntry(entries.startSubCategory(Component.literal(name), list).setTooltip(lines(tooltip)).build());
		}

		/** Cloth's toggles have no setter: press each one's own button (its first child) where it differs. */
		private static void setAll(BooleanListEntry[] toggles, boolean on) {
			for (BooleanListEntry toggle : toggles) {
				if (toggle.getValue() != on) ButtonRowEntry.press((Button) toggle.children().get(0));
			}
		}
	}
}
