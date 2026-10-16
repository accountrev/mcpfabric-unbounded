# Supervised mode

The supervised mode (issue #27) lets a model take single, narrow actions under authority that
runs out on its own, instead of holding operator-level control for as long as it is connected.
It is off by default and sits next to the other capability groups, which keep working as before.

## Turning it on

In `config/mcpfabric.config.json`:

```json
{
  "enableSupervisedControl": true,
  "supervisedWorlds": ["Test World"],
  "supervisedCommands": ["time", "weather"]
}
```

- `enableSupervisedControl` is its own lock. It does not need `enablePlayerControl`,
  `enableWorldWrite` or `enableCommands`. Turn those off and the model can only take supervised
  actions.
- `supervisedWorlds` lists the save folders (as in `list_worlds`, without `sp:`) that
  `supervised_enter_world` may open. Use disposable copies. Empty means none.
- `supervisedCommands` lists the command roots (first word) `supervised_command` may run. Block and
  command data (`{...}`) is refused everywhere in this mode.

The bridge stays on `127.0.0.1` with its bearer token. `get_status` and `list_capabilities` report
the group as `supervised`.

## How one action runs

1. The model calls a supervised tool. Each call is one intent, and an MCP client that asks before
   running tools asks for every one of them.
2. The MCP server adds its supervisor session id and the next sequence number. The session is
   opened with `supervised.begin` on the first call, and the model never sees its id. A request
   from an older session, or with a sequence number the mod has already seen, is refused
   (`stale_session`, `replayed`). A new session takes over from the previous one.
3. The mod refuses the request while an earlier action has no known outcome (`unreconciled`) or
   when the operation cannot run on this side (`not_available`). Otherwise it issues a grant. The
   grant names the exact target (a block position and dimension, a command, a key, a save), is
   bound to the current context (the world and dimension the player is in, or the menus) and
   expires after 2 seconds. It cannot be renewed.
4. On the game thread the effect admits the grant before acting. STOP, a context change, another
   target, an expired grant or a grant that was already used are turned down there, so work that
   was queued before any of them never acts.
5. The answer carries the `actionId` and the `outcome`.

| Outcome | Meaning |
|---|---|
| `applied` | The effect happened and was read back. |
| `not_applied` | Nothing changed. Refused at admission, or a precondition did not hold (`reason` says which). |
| `interrupted` | An input sample ended early (STOP, the key was released, the world or dimension changed). Its key was released. |
| `pending` | Started in the background (entering or leaving a world). Reconcile it. |
| `uncertain` | It may or may not have happened, for example when the game thread did not answer in time. Reconcile it. |
| `acknowledged` | Reconciled without a readback: the caller accepted that the outcome is unknown. |

While an action is `pending` or `uncertain`, no other supervised action runs. Call
`supervised_reconcile`: it reads the game again (the block, the world joined or left) and settles the
action. Nothing is retried automatically. For a command, which has nothing to read back, pass
`acknowledge: true`.

## Operations

| Tool | Side | What it does |
|---|---|---|
| `supervised_input` | client | Holds one movement key (forward, back, left, right) for 1-10 ticks, then releases it. |
| `supervised_read_block` | server | Reads one block. |
| `supervised_set_block` | server | Replaces one block, only if the block there is `expect` right now, and reads it back. |
| `supervised_command` | server | Runs one command whose root is in `supervisedCommands`. |
| `supervised_enter_world` | client | Opens a save from `supervisedWorlds`, from the menus. |
| `supervised_leave_world` | client | Saves and quits the singleplayer world the player is in (`worldId` must name it). |
| `supervised_reconcile` | both | Settles a pending or uncertain action. |
| `supervised_status` | both | Which operations can run now and why not, open and recent actions, held keys, limits. |
| `supervised_stop` | both | STOP. Always available, even without `enableSupervisedControl`. |

"Server" operations need an integrated or dedicated server. On a dedicated server the client
operations are reported as unavailable, with the reason. `supervised_status` only reports an
operation as available when this side implements it and it can run right now.

## Input ownership

A supervised input sample never takes a key the player already holds (`player_holds_key`). It is
refused while a screen is open or while `set_movement` or navigation drives the keys. If the key
goes up while the sample holds it (the player, or a screen that opened), the key now belongs to
whoever released it: the sample ends as `interrupted` and does not press it again. A key release
that fails stays pending and is retried every tick. `supervised_status` lists the held keys and
the ones still waiting for cleanup.

## Limits

- STOP fences new admissions and releases supervised input. It does not interrupt or roll back an
  effect that was already admitted, such as a block that was set.
- The 2-second grant bounds admission, not the effect. An input sample is bounded by its tick count,
  not by a physical distance.
- Leaving a world, the cleanup and process exit do not prove that the save reached the disk.
- Uncertain mutations are not retried. Reconcile them, or acknowledge them.
- One supervisor session at a time: a second MCP client taking supervised actions takes over the
  session, and the first one gets `stale_session` once.
