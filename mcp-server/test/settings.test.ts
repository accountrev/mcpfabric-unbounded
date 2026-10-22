import assert from "node:assert/strict";
import { test } from "node:test";
import { z } from "zod";

import { JobManager } from "../src/agent/jobs.js";
import { AgentRuntime } from "../src/agent/runtime.js";
import { fmtSlotDetails } from "../src/agent/skills.js";
import { AGENT_TOOLS, AgentSession } from "../src/agent/tools.js";
import { capped, DEFAULT_SETTINGS, SettingsCache } from "../src/settings.js";
import { FakeWorld } from "./fake-world.js";

test("settings: the mod's defaults until the game answers, then its values", async () => {
  const world = new FakeWorld();
  const read: number[] = [];
  assert.deepEqual(await new SettingsCache(world).get(), DEFAULT_SETTINGS, "a mod without info.settings keeps the defaults");

  world.settings = { recallMaxLimit: 5, bridgeTimeoutMs: 2000 };
  world.calls.length = 0;
  const cache = new SettingsCache(world, (s) => read.push(s.bridgeTimeoutMs));
  assert.equal(cache.now().recallMaxLimit, 50, "now() answers at once with what it knows");
  const s = await cache.get();
  assert.equal(s.recallMaxLimit, 5);
  assert.equal(s.itemTypesShown, DEFAULT_SETTINGS.itemTypesShown, "fields the game leaves out keep their default");
  await cache.get();
  assert.equal(world.calls.filter((m) => m === "info.settings").length, 1, "read again only after a while");
  assert.deepEqual(read, [2000]);
});

test("capped refuses values above the limit and lowers a default to it", () => {
  assert.throws(() => capped("count", 300, 1, 256), /count may be at most 256/);
  assert.equal(capped("count", 256, 1, 256), 256);
  assert.equal(capped("radius", undefined, 48, 128), 48);
  assert.equal(capped("radius", undefined, 48, 20), 20);
});

test("agent tools apply the game's limits", async () => {
  const world = new FakeWorld();
  world.settings = { recallMaxLimit: 5, mapViewMaxRadius: 3 };
  const rt = new AgentRuntime(world, { dbPath: ":memory:" });
  const session = new AgentSession();
  const tool = (name: string, args: Record<string, unknown> = {}) => {
    const def = AGENT_TOOLS.find((t) => t.name === name)!;
    return def.run(rt, session, z.object(def.inputSchema).parse(args));
  };
  await assert.rejects(tool("recall", { limit: 6 }), /limit may be at most 5/);
  await tool("recall", { limit: 5 });
  await tool("observe");
  assert.match(await tool("map_view"), /north/, "the default radius (10) is lowered to the limit instead of refused");
  await assert.rejects(tool("map_view", { radius: 4 }), /radius may be at most 3/);
  rt.close();
});

test("job manager keeps the configured number of log lines and finished jobs", async () => {
  const jobs = new JobManager(undefined, undefined, () => ({ maxMs: 60_000, logLines: 2, history: 1 }));
  const a = jobs.start("log", "five lines", async (ctx) => {
    for (let i = 1; i <= 5; i++) ctx.log(`line ${i}`);
    return "ok";
  });
  assert.deepEqual((await jobs.wait(a.id, 1000))!.log, ["line 4", "line 5"]);
  const b = jobs.start("second", "x", async () => "ok");
  await jobs.wait(b.id, 1000);
  assert.equal(jobs.get(a.id), undefined, "only the latest finished job is kept");
});

test("container slot details list the items that carry components", () => {
  const text = fmtSlotDetails({
    open: true,
    containerId: 1,
    size: 27,
    slots: [
      { slot: 0, id: "minecraft:diamond_sword", count: 1, components: { "minecraft:damage": 5 } },
      { slot: 1, id: "minecraft:dirt", count: 3 },
    ],
  });
  assert.equal(text, '\n  slot 0 diamond_sword×1: {"minecraft:damage":5}');
});
