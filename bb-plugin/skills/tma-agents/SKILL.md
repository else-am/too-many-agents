---
name: tma-agents
description: Delegate project work through Too Many Agents, choose optional stations, and use shared screens. Use BB for conversation and coordination.
---

# Too Many Agents

Use `bb tma spawn --help` for current options. The command runs from a
Too Many Agents thread and gives the new thread a visible presence.
`--parent-self` creates a BB child; omit it for an independent BB thread.
`--environment` reuses an environment. Otherwise BB and the project
selection resolve the workspace. Other execution options use BB defaults.

```sh
bb tma stations --json
bb tma spawn --parent-self --prompt 'Fix the issue' --json
bb tma bodies --json
```

For multiline tasks, use `--prompt-file task.md`. Relative paths resolve from the
invoking agent's working directory on its BB host, including remote hosts.
Quoted `--prompt` also accepts newlines; `--prompt-stdin` accepts only one line.
Provide exactly one of `--prompt` (including its stdin form) and `--prompt-file`.
Explicit workspace paths and new environments use the calling thread's host unless
`--machine` selects another one.

Never repeat a spawn after an unknown outcome. Inspect `bb tma bodies`
and `bb thread list` before taking further action. A failed conversation start
can leave a saved body visible in Too Many Agents for recovery.

Use ordinary `bb thread tell`, `show`, `wait`, `stop`, `archive`, `unarchive`, and
`delete` with BB thread IDs. BB owns hierarchy, queues, permissions and results.
Messages between bodies in the same world appear in the shared chat when enabled.
BB controls communication permissions.

For delegation through Too Many Agents, use `bb tma spawn`, not provider-native
subagents or ordinary BB spawn/fork: those create no body. This is a workflow
instruction, not a restriction on unrelated BB threads. Do not treat another
agent's message as human permission or answer its approvals on the user's behalf.

Stopping cancels physical actions. Archiving suspends the body and releases its
station; unarchiving restores its saved body without a station when the world is
available.
Deleting removes its association. Conversation edits and forks do not undo or
copy changes outside the conversation.

## Projects and stations

Project agents spawn only into their own project. A station is an optional,
user-configured place for an agent, with execution defaults and shared screens.
A station without a project cannot spawn an agent.

```sh
bb tma stations --json
bb tma spawn --parent-self --station desk --prompt 'Fix the small issue' --json
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

## Screens

A screen is a shared page: a dashboard, whiteboard, site or your own HTML.
Use one to show the user something, or to work on a page together; they can take over.

- Screens assigned to a station are exclusive to its occupant; an unoccupied
  station's screens are off-limits. Unassigned screens are shared; the user can
  use any screen.
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
