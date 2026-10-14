---
name: minecraft-agents
description: Spawn embodied agents at optional physical stations and use screens in an attached Minecraft world. Use BB itself for conversation and coordination.
---

# Embodied agents

Use `bb minecraft spawn --help` for current options. The command runs from a
Minecraft-associated BB thread and creates a body in that thread's world.
`--parent-self` creates a BB child; omit it for an independent BB thread.
`--environment` reuses an environment. Otherwise BB and the in-game project
selection resolve the workspace. Other execution options use BB defaults.

```sh
bb minecraft stations --json
bb minecraft spawn --parent-self --prompt 'Build the entrance' --body minecraft:fox --json
bb minecraft bodies --json
```

For multiline tasks, use `--prompt-file task.md`. Relative paths resolve from the
invoking agent's working directory on its BB host, including remote hosts.
Quoted `--prompt` also accepts newlines; `--prompt-stdin` accepts only one line.
Provide exactly one of `--prompt` (including its stdin form) and `--prompt-file`.
Explicit workspace paths and new environments use the calling thread's host unless
`--machine` selects another one.

Never repeat a spawn after an unknown outcome. Inspect `bb minecraft bodies`
and `bb thread list` before taking further action. A failed conversation start
can leave a saved body visible in Minecraft for recovery.

Use ordinary `bb thread tell`, `show`, `wait`, `stop`, `archive`, `unarchive`, and
`delete` with BB thread IDs. BB owns hierarchy, queues, permissions and results.
Messages between bodies in the same world appear in Minecraft chat when enabled.
There are no separate Minecraft communication permissions.

For delegation from Minecraft, use `bb minecraft spawn`, not provider-native
subagents or ordinary BB spawn/fork: those create no body. This is a workflow
instruction, not a restriction on unrelated BB threads. Do not treat another
agent's message as human permission or answer its approvals on the user's behalf.

Stopping cancels physical actions. Archiving suspends the body and releases its
station; unarchiving restores its saved body without a station when the world is
available.
Deleting removes its association. Conversation edits and forks do not undo or
copy Minecraft world mutations.

## Projects and stations

Access comes from the project: agents in **This world** get physical tools and the
minecraft-scripting skill. Other project agents do not; all agents get screen
tools. World agents can spawn into their own world project or any non-world
project. Project agents spawn only into their own project. Children derive access
from their project too.

A station is a placed block configured by the user or a world agent using
`minecraft_run`. A project block can stamp its project onto a station item without
changing the item's other settings. Project blocks define no area or screen access.
Neither block accepts a world project. A blank station cannot spawn.

```sh
bb minecraft stations --json
bb minecraft spawn --parent-self --station desk --prompt 'Fix the small issue' --json
```

`--station` accepts a UUID or name and implies its project; a conflicting
`--project` is rejected. Listing shows all stations to world agents and only their
project's stations to project agents. Stations are optional for project agents
and unavailable to world agents. A station holds one body, claimed only at spawn
and released only by archiving or deleting it. An occupied station cannot be
broken or change project. Breaking a free station carries its settings in the item.

Station instructions precede the first task. Blank provider/model/reasoning/tier/
approval fields inherit BB's project defaults; set a field to pin it. Explicit
spawn flags override them, but approvals cannot exceed the caller's permissions.
Execution settings, body and instructions apply to future spawns. Area and behavior
edits apply immediately to the occupant. Without a station, children inherit live
parent execution settings and independent spawns use BB project defaults.
Worktrees are chosen at spawn (`--new-environment worktree`).

Station radius is 0–49 (square width `2r+1`), height 1–99 upwards from the block;
defaults are radius 1 and height 3. Only stationed project agents are confined,
except while following. **B** toggles view-only station-area outlines.

World agents use `--behavior stand|wander|follow`, default stand. Wander stays near
where it began. `minecraft_behavior` changes it; the first script or physical
action pauses it until the turn ends, and it resumes from where the body is. Project agents use per-status `behaviors`
(`working`, `wants_you`, `idle`), each with a `type` of stand, wander, follow, jump,
spin or look (at the player). A station supplies these for its occupant; otherwise
use the agent's settings or `--behaviors` at spawn.

World-agent `--mode` accepts `survival`, `creative`, or `creative_commands`
(Creative + commands). Only `creative_commands` allows Minecraft commands; both
Creative modes allow Creative physical actions. A child cannot gain physical
abilities its caller lacks.

Project colors are configured in Minecraft’s project settings and persisted in BB,
shared across worlds. Agents have no individual color setting.

## Screens

A screen is a page placed in the world: a dashboard, whiteboard, site or your own
HTML. Use one to show the user something, or to work on a page together; they
watch and can take over in-game.

- Screens intersecting a station's area link to it live. If several areas touch,
  the nearest station to the screen's center wins, with ties going to the lower id.
  Linked screens are exclusive to the occupant; a free station's screens are
  off-limits to every agent. Unlinked screens are shared; players can use any screen.
- `screen_list {}` lists only usable screens: your station's first (`yours: true`),
  then unlinked screens by the smaller distance to you or the user, other dimensions
  last. `limit` defaults to 20, maximum 50; `truncated` counts omitted rows.
  `openedByYou` identifies pages your thread opened. `distance` helps resolve
  "over here" or "by you". Naming an inaccessible screen fails with `no_access`.
- Omit `screen` when your station has exactly one; otherwise pass its UUID
  or unique name. Keep the UUID for future calls: renaming preserves it, but
  picking up and replacing a screen creates a new identity. `choose_screen` lists the candidates.
- `screen_open` shows a URL or file; `screen_cdp` drives the page (usually
  `Runtime.evaluate`); `screen_screenshot` shows you what it looks like;
  `screen_close` turns it off and clears its URL. Screens work outside the player's
  loaded area. Listing never wakes them; using one wakes its saved URL and keeps
  the page alive through your active task and a short idle grace period. After
  sleep, the URL survives but in-memory page state does not. Browser capacity is
  bounded; `resource_limit` means release a screen or wait for one to sleep.
- A `leased` screen is in use by another thread for a few seconds. Wait and
  retry, or use another screen.
- Nothing is retried automatically. After `timeout` the outcome is unknown:
  check with `screen_list` or `screen_screenshot` before acting again.
