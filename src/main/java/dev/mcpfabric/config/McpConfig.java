package dev.mcpfabric.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.mcpfabric.McpFabric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persistent configuration for the bridge, stored at {@code config/mcpfabric.config.json}.
 * An auth token is generated on first run and remains in the config file so it does not leak into
 * logs. Copy it into the MCP server's {@code MCPFABRIC_TOKEN} environment variable.
 */
public final class McpConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Bind host. Keep on loopback unless you really know what you are doing. */
	public String host = "127.0.0.1";
	public int port = 25599;
	/** Shared secret required in the Authorization: Bearer header. */
	public String token = "";
	/**
	 * When false, any program on this machine can use the bridge without the token. Web pages are still
	 * refused (they send an Origin header), but binding to loopback alone would not stop them.
	 */
	public boolean requireAuth = true;

	/** Max time a single RPC may block the game thread before timing out. */
	public int callTimeoutMs = 8000;

	/**
	 * Secret that turns a server address into an opaque world id (session.info), so the agent's memory
	 * stays per server without the address reaching the MCP client or its database. Generated on first
	 * run; never sent over the bridge.
	 */
	public String worldIdKey = "";

	// capability gates ------------------------------------------------------------------------
	public boolean enableWorldWrite = true;
	public boolean enableCommands = true;
	public boolean enablePlayerControl = true;
	public boolean enableVision = true;
	/** Generic control of game screens (ui.*). Off by default; also needs enablePlayerControl. */
	public boolean enableUiControl = false;
	/**
	 * Supervised mode (supervised.*): one narrow action per call under short-lived authority. Off by
	 * default. It is its own lock and does not need the flags above, so they can stay off.
	 */
	public boolean enableSupervisedControl = false;
	/** Save folders supervised_enter_world may open (disposable worlds). Empty: none. */
	public List<String> supervisedWorlds = new ArrayList<>();
	/** Command roots supervised_command may run; data ({...}) is refused in every command. */
	public List<String> supervisedCommands = new ArrayList<>(List.of("time", "weather"));

	/** How much one call may read or return. Also editable on the config screen (Cloth Config). */
	public Limits limits = new Limits();
	/** Limits the MCP server applies itself; it reads them over the bridge (info.settings). */
	public McpServer mcpServer = new McpServer();
	/** Extra data added to items, entities and blocks in answers. All off by default. */
	public Data data = new Data();

	public transient Path source;

	/** How {@link Limits#scanRadiusChunks} or {@link Limits#scanRadiusPercent} caps the perception scan. */
	public enum RadiusMode { CHUNKS, PERCENT }

	/**
	 * Caps the mod enforces. A request above one is refused (or clamped, where the method always
	 * clamped); a default above one is lowered to it. Several protect the game thread: a region read,
	 * a block search or a path search runs on it, so raising them can stall the game.
	 */
	public static final class Limits {
		public int nearbyEntitiesMax = 64;
		public int nearbyEntitiesRadius = 64;
		public int sceneEntitiesMax = 16;
		public int sceneMaxDistance = 128;
		public int sceneMaxRays = 33;
		public RadiusMode scanRadiusMode = RadiusMode.CHUNKS;
		public int scanRadiusChunks = 12;
		public int scanRadiusPercent = 100;
		public int scanMaxPois = 400;
		public int scanFindLimitMax = 500;
		public int blockProbesMax = 512;
		public int uiMaxWidgets = 200;
		public int uiMaxText = 256;
		public int recipeMaxAlternatives = 16;
		public int navNodeBudget = 12_000;
		public int regionMaxVolume = 16_777_216;
		public int regionDefaultMaxBlocks = 32_768;
		public int regionMaxBlocks = 200_000;
		public int findBlocksScanBudget = 250_000;
		public int findBlocksMaxRadius = 128;
		public int findBlocksMaxResults = 1024;
		public int entitiesQueryMaxRadius = 256;
		public int entitiesQueryMaxResults = 1000;
		public int raycastMaxDistance = 256;
		public int giveItemMaxCount = 6400;
		public int eventBufferSize = 2000;
		public int eventsMaxLimit = 500;
		public int sseQueueSize = 2000;
		public int sseMaxSubscribers = 16;
		public int maxBodyBytes = 1 << 20;
	}

	/** Field names match DEFAULT_SETTINGS in mcp-server/src/settings.ts, whose defaults apply until the game answers. */
	public static final class McpServer {
		public int itemTypesShown = 24;
		public int briefItemTypesShown = 20;
		public int containerItemTypesShown = 60;
		public int observeFindLimit = 24;
		public int observeFoundShown = 12;
		public int observeNewPlacesShown = 12;
		public int observeOreTypesShown = 10;
		public int exploreNewPlacesShown = 15;
		public int recipesShown = 12;
		public int recipeAlternativesShown = 3;
		public int planCraftStoresRecalled = 100;
		public int planCraftStoresShown = 5;
		public int recallMaxLimit = 50;
		public int recallResourceChunks = 5;
		public int memoryBodyChars = 160;
		public int briefMemoryBodyChars = 90;
		public int briefNearbyMemories = 8;
		public int briefMapRadius = 5;
		public int mapViewMaxRadius = 24;
		public int jobLogLines = 40;
		public int jobLogShown = 8;
		public int jobHistory = 10;
		public int collectBlocksMaxCount = 256;
		public int collectBlocksMaxRadius = 128;
		public int collectBlocksMaxSeconds = 1800;
		public int craftItemMaxCount = 640;
		public int planCraftMaxCount = 4096;
		public int bridgeTimeoutMs = 15_000;
		public int jobMaxMinutes = 31;
		public int httpSessionIdleMinutes = 30;
		public int maxHttpSessions = 32;
	}

	/** Extra data by group; one toggle per kind of data. */
	public static final class Data {
		public ItemComponents items = new ItemComponents();
		public EntityDetails entities = new EntityDetails();
		public EntityNbt entityNbt = new EntityNbt();
		public BlockStates blockStates = new BlockStates();
		public BlockEntities blockEntities = new BlockEntities();
	}

	public static final class ItemComponents {
		public boolean enchantments = false;
		public boolean names = false;
		public boolean lore = false;
		public boolean durability = false;
		public boolean potions = false;
		public boolean attributeModifiers = false;
		public boolean customData = false;
		public boolean other = false;

		public boolean any() {
			return enchantments || names || lore || durability || potions || attributeModifiers || customData || other;
		}

		/** Whether a component of an encoded patch is sent; {@code !} marks a default component the stack removed. */
		public boolean sends(String componentId) {
			return switch (componentId.startsWith("!") ? componentId.substring(1) : componentId) {
				case "minecraft:enchantments", "minecraft:stored_enchantments" -> enchantments;
				case "minecraft:custom_name", "minecraft:item_name" -> names;
				case "minecraft:lore" -> lore;
				case "minecraft:damage", "minecraft:max_damage", "minecraft:unbreakable" -> durability;
				case "minecraft:potion_contents", "minecraft:suspicious_stew_effects" -> potions;
				case "minecraft:attribute_modifiers" -> attributeModifiers;
				case "minecraft:custom_data" -> customData;
				default -> other;
			};
		}
	}

	public static final class EntityDetails {
		public boolean motion = false;
		public boolean equipment = false;
		public boolean attributes = false;
		public boolean effects = false;
		public boolean item = false;
	}

	public static final class EntityNbt {
		public boolean players = false;
		public boolean others = false;
	}

	public static final class BlockStates {
		public boolean regions = false;
		public boolean searches = false;
	}

	public static final class BlockEntities {
		public boolean containers = false;
		public boolean signs = false;
		public boolean spawners = false;
		public boolean other = false;
	}

	public static McpConfig load() {
		Path dir = McpFabric.platform().configDir();
		Path file = dir.resolve("mcpfabric.config.json");
		McpConfig cfg;
		if (Files.exists(file)) {
			try {
				cfg = GSON.fromJson(Files.readString(file), McpConfig.class);
				if (cfg == null) cfg = new McpConfig();
			} catch (Exception e) {
				McpFabric.LOGGER.error("[mcpfabric] failed to read config, using defaults", e);
				cfg = new McpConfig();
			}
		} else {
			cfg = new McpConfig();
		}
		if (cfg.token == null || cfg.token.isBlank()) {
			cfg.token = UUID.randomUUID().toString().replace("-", "");
		}
		if (cfg.worldIdKey == null || cfg.worldIdKey.isBlank()) {
			cfg.worldIdKey = UUID.randomUUID().toString().replace("-", "");
		}
		if (cfg.supervisedWorlds == null) cfg.supervisedWorlds = new ArrayList<>();
		if (cfg.supervisedCommands == null) cfg.supervisedCommands = new ArrayList<>();
		if (cfg.limits == null) cfg.limits = new Limits();
		// Gson reads an unknown enum name as null.
		if (cfg.limits.scanRadiusMode == null) cfg.limits.scanRadiusMode = RadiusMode.CHUNKS;
		if (cfg.mcpServer == null) cfg.mcpServer = new McpServer();
		if (cfg.data == null) cfg.data = new Data();
		if (cfg.data.items == null) cfg.data.items = new ItemComponents();
		if (cfg.data.entities == null) cfg.data.entities = new EntityDetails();
		if (cfg.data.entityNbt == null) cfg.data.entityNbt = new EntityNbt();
		if (cfg.data.blockStates == null) cfg.data.blockStates = new BlockStates();
		if (cfg.data.blockEntities == null) cfg.data.blockEntities = new BlockEntities();
		cfg.source = file;
		cfg.save();
		return cfg;
	}

	public void save() {
		try {
			if (source == null) {
				source = McpFabric.platform().configDir().resolve("mcpfabric.config.json");
			}
			Files.createDirectories(source.getParent());
			Files.writeString(source, GSON.toJson(this));
		} catch (IOException e) {
			McpFabric.LOGGER.error("[mcpfabric] failed to write config", e);
		}
	}
}
