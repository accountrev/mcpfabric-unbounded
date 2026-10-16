import assert from "node:assert/strict";
import { test } from "node:test";
import { z } from "zod";

import { BridgeError, type BridgeClient } from "../src/bridge.js";
import { SupervisedSession } from "../src/supervised.js";
import { TOOLS } from "../src/tools.js";

interface Call {
  method: string;
  params: Record<string, unknown>;
}

/** A bridge that opens numbered sessions and answers with what it was called with. */
function fakeBridge(fail?: (call: Call) => BridgeError | undefined) {
  const calls: Call[] = [];
  let sessions = 0;
  const bridge = {
    async call(method: string, params: Record<string, unknown> = {}) {
      const call = { method, params };
      calls.push(call);
      await new Promise((resolve) => setTimeout(resolve, 1));
      if (method === "supervised.begin") return { session: `s${++sessions}` };
      const err = fail?.(call);
      if (err) throw err;
      return { echoed: method, seq: params.seq };
    },
  } as unknown as BridgeClient;
  return { bridge, calls };
}

test("opens a session on the first call and numbers every request", async () => {
  const { bridge, calls } = fakeBridge();
  const session = new SupervisedSession(bridge);
  await session.call("supervised.readBlock", { x: 1, y: 2, z: 3 });
  await session.call("supervised.command", { command: "time set day" });
  assert.deepEqual(
    calls.map((c) => c.method),
    ["supervised.begin", "supervised.readBlock", "supervised.command"],
  );
  assert.deepEqual(calls[1]!.params, { x: 1, y: 2, z: 3, session: "s1", seq: 1 });
  assert.equal(calls[2]!.params.seq, 2);
});

test("the model cannot choose the session or the sequence number", async () => {
  const { bridge, calls } = fakeBridge();
  const session = new SupervisedSession(bridge);
  await session.call("supervised.input", { direction: "forward", session: "forged", seq: 99 });
  assert.equal(calls[1]!.params.session, "s1");
  assert.equal(calls[1]!.params.seq, 1);
});

test("runs concurrent calls one at a time, in order", async () => {
  const { bridge, calls } = fakeBridge();
  const session = new SupervisedSession(bridge);
  await Promise.all([
    session.call("supervised.readBlock", { x: 0 }),
    session.call("supervised.readBlock", { x: 1 }),
    session.call("supervised.readBlock", { x: 2 }),
  ]);
  const reads = calls.filter((c) => c.method === "supervised.readBlock");
  assert.deepEqual(reads.map((c) => [c.params.x, c.params.seq]), [[0, 1], [1, 2], [2, 3]]);
  assert.equal(calls.filter((c) => c.method === "supervised.begin").length, 1);
});

test("a stale session is dropped and the next call opens a new one", async () => {
  let failOnce = true;
  const { bridge, calls } = fakeBridge(() => {
    if (!failOnce) return undefined;
    failOnce = false;
    return new BridgeError({ code: "stale_session", message: "another supervisor took over" });
  });
  const session = new SupervisedSession(bridge);
  await assert.rejects(session.call("supervised.readBlock", {}), (err: unknown) => err instanceof BridgeError && err.code === "stale_session");
  await session.call("supervised.readBlock", {});
  const last = calls.at(-1)!;
  assert.equal(last.params.session, "s2");
  assert.equal(last.params.seq, 1);
});

test("other errors keep the session, and a failed call does not block the next", async () => {
  let failOnce = true;
  const { bridge, calls } = fakeBridge(() => {
    if (!failOnce) return undefined;
    failOnce = false;
    return new BridgeError({ code: "unreconciled", message: "reconcile a1 first" });
  });
  const session = new SupervisedSession(bridge);
  await assert.rejects(session.call("supervised.setBlock", {}));
  await session.call("supervised.reconcile", { actionId: "a1" });
  assert.equal(calls.at(-1)!.params.session, "s1");
  assert.equal(calls.at(-1)!.params.seq, 2);
});

test("ending the session tells the mod once", async () => {
  const { bridge, calls } = fakeBridge();
  const session = new SupervisedSession(bridge);
  await session.end();
  assert.equal(calls.length, 0, "nothing to end before the first call");
  await session.call("supervised.readBlock", {});
  await session.end();
  await session.end();
  assert.deepEqual(calls.at(-1), { method: "supervised.end", params: { session: "s1" } });
  assert.equal(calls.filter((c) => c.method === "supervised.end").length, 1);
});

test("supervised tools carry no session or seq in their schema, and status and stop need no session", () => {
  const supervised = TOOLS.filter((t) => t.method.startsWith("supervised."));
  assert.deepEqual(supervised.map((t) => t.name).sort(), [
    "supervised_command",
    "supervised_enter_world",
    "supervised_input",
    "supervised_leave_world",
    "supervised_read_block",
    "supervised_reconcile",
    "supervised_set_block",
    "supervised_status",
    "supervised_stop",
  ]);
  for (const tool of supervised) {
    assert.ok(!("session" in tool.inputSchema) && !("seq" in tool.inputSchema), tool.name);
    const needsSession = !["supervised_status", "supervised_stop"].includes(tool.name);
    assert.equal(tool.supervised === true, needsSession, tool.name);
  }
  const input = z.object(TOOLS.find((t) => t.name === "supervised_input")!.inputSchema);
  assert.equal(input.parse({ direction: "left" }).ticks, 4);
  assert.throws(() => input.parse({ direction: "up" }));
  assert.throws(() => input.parse({ direction: "forward", ticks: 11 }));
  const setBlock = z.object(TOOLS.find((t) => t.name === "supervised_set_block")!.inputSchema);
  assert.throws(() => setBlock.parse({ x: 0.5, y: 64, z: 0, block: "stone", expect: "air" }), "positions are whole blocks");
});
