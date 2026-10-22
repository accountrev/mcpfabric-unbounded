/**
 * Limits the MCP server applies itself: how much the agent tools show and accept, job and HTTP
 * session limits, the bridge timeout. They live in the game's mcpfabric.config.json (section
 * `mcpServer`, also on the mod's config screen) and are read over the bridge with `info.settings`.
 */

/** The mod's defaults (McpConfig.McpServer); they apply until the game answers, or with an older mod. */
export const DEFAULT_SETTINGS = {
  itemTypesShown: 24,
  briefItemTypesShown: 20,
  containerItemTypesShown: 60,
  observeFindLimit: 24,
  observeFoundShown: 12,
  observeNewPlacesShown: 12,
  observeOreTypesShown: 10,
  exploreNewPlacesShown: 15,
  recipesShown: 12,
  recipeAlternativesShown: 3,
  planCraftStoresRecalled: 100,
  planCraftStoresShown: 5,
  recallMaxLimit: 50,
  recallResourceChunks: 5,
  memoryBodyChars: 160,
  briefMemoryBodyChars: 90,
  briefNearbyMemories: 8,
  briefMapRadius: 5,
  mapViewMaxRadius: 24,
  jobLogLines: 40,
  jobLogShown: 8,
  jobHistory: 10,
  collectBlocksMaxCount: 256,
  collectBlocksMaxRadius: 128,
  collectBlocksMaxSeconds: 1800,
  craftItemMaxCount: 640,
  planCraftMaxCount: 4096,
  bridgeTimeoutMs: 15_000,
  jobMaxMinutes: 31,
  httpSessionIdleMinutes: 30,
  maxHttpSessions: 32,
};

export type Settings = typeof DEFAULT_SETTINGS;

const TTL_MS = 15_000;

/** What info.settings answers. */
interface SettingsReply {
  mcpServer?: Partial<Settings>;
}

interface SettingsBridge {
  call<T = unknown>(method: string, params?: Record<string, unknown>): Promise<T>;
}

export class SettingsCache {
  private value: Settings = DEFAULT_SETTINGS;
  private at = 0;
  private pending: Promise<Settings> | undefined;

  constructor(
    private readonly bridge: SettingsBridge,
    /** Called with every successful read. */
    private readonly onRead: (s: Settings) => void = () => {},
  ) {}

  /** The game's settings, read again at most every 15 s; the last known ones when it does not answer. */
  async get(): Promise<Settings> {
    if (Date.now() - this.at < TTL_MS) return this.value;
    this.pending ??= this.read().finally(() => (this.pending = undefined));
    return this.pending;
  }

  /** The last known settings without waiting; starts a read when they are stale. */
  now(): Settings {
    void this.get();
    return this.value;
  }

  private async read(): Promise<Settings> {
    try {
      const r = await this.bridge.call<SettingsReply>("info.settings");
      this.value = { ...DEFAULT_SETTINGS, ...r.mcpServer };
      this.onRead(this.value);
    } catch {
      // game not running, or a mod without info.settings: keep the last known values
    }
    this.at = Date.now();
    return this.value;
  }
}

/**
 * An argument checked against a configured limit: an explicit value above it is refused, a missing
 * one becomes `fallback`, lowered to the limit.
 */
export function capped(name: string, value: number | undefined, fallback: number, max: number): number {
  if (value === undefined) return Math.min(fallback, max);
  if (value > max) throw new Error(`${name} may be at most ${max} (a limit in the mod config)`);
  return value;
}
