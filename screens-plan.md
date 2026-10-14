# Screens

A screen is a window onto a URL that you place in the world. Task boards, dashboards, whiteboards, VNC clients, staging sites and agents' HTML visualizations are all just pages. The mod renders pages and never needs to know what is on them.

You place a screen and ask an agent to set something up on it. You and agents use the same live page: the agent drives it over CDP, and you watch or take over in-game.

## Decided

### Screens in the world

- ~~A screen is one entity, like a painting: a thin black panel with a slim bezel, extending vanilla's `HangingEntity`. Same crafting recipe as now; one item makes one screen.~~
- ~~Painting rules for support, from `HangingEntity.survives()`: each block behind must be solid (or a repeater/comparator), nothing may collide with it, and it can't overlap another hanging entity. Whatever works for paintings works for screens.~~
- ~~Placing: right-click a wall to set one corner. The screen is then partly placed: a preview rectangle follows your aim along that wall, in any direction, up to 16x16. Cells that fail the support rules show as invalid. Right-click again to confirm; switching items or Esc cancels.~~
- ~~Screens cannot be broken by attacking, with any item or in creative. Bare hands interact with the page. The panel has an opaque black back.~~
- ~~The bottom bar's ⋯ menu opens upward and offers **Turn off (clear page)** (power icon), **Pick up (save page)** (arrow out of a tray), and **Reset and pick up** (reset arrow). Turn off keeps the id/name. Pick up saves only name and URL; reset asks for a second click and saves neither. Both pop one item off the panel into the world, including in creative. Icons are pixel art at the same pixel scale as text.~~
- ~~A screen whose wall is removed drops its item carrying name and URL, using vanilla's periodic support check unchanged. Every placement allocates a fresh id and starts with no openedBy; a placed screen retains its id across reloads.~~
- ~~Items never carry an id or openedBy. Matching name and URL stack. Their tooltip shows name - URL (or whichever is set). Any single screen item can be crafted into one plain screen.~~
- ~~No migration: block screens and screens built before this design are discarded, not converted.~~

### Screen record

~~The screen's id, name, URL and `openedBy` are saved on the screen entity and reach the client as ordinary synced entity data:~~

```java
record Screen(String id, String name, String url, String openedBy) {}
```

- ~~Ids are entity UUIDs, stable across reloads and renames, hidden from the player-facing bar. Pickup ends the identity. Placed screens have nonblank, case-insensitively unique names across the world, including unloaded chunks. Blank items get readable adjective-animal names; saved-name collisions receive numeric suffixes. Explicit blank or duplicate renames are rejected.~~
- ~~A small server-side index (id, name, URL, position) lets `screen_list` include placed screens in unloaded chunks. A screen in an item is not listed.~~
- ~~Players name a screen by clicking the name on its bar; agents name one through `screen_open`.~~
- ~~`url` is empty when the screen is idle, meaning no Chromium browser exists for it.~~
- ~~`openedBy` is the thread that last opened the page. It's set by `screen_open`, and cleared when a player navigates or the screen is closed.~~
- Planned `terminal` and `review` pages will use our own `tma://` scheme; see Later.

### Access and defaults

Superseded by the [stations decision](stations-plan.md#screens). Screens link live
by intersection with station areas, nearest to the screen's center winning overlaps
(ties: lower station id). Linked screens are exclusive to the occupant; a free
station's screens are off-limits to every agent. Unlinked screens are shared, and
players can always use any screen. Projects do not own screens.

Omitting `screen` uses the caller's sole linked screen. Several linked screens
return `choose_screen` with candidates; without one, pass a UUID or unique name.
Keep UUIDs across renames; pickup and replacement creates a new identity.

### Tools

~~All five are ungated, so every mod agent gets them, in either world or other projects. Responses avoid Minecraft details: threads are identified by id, and sizes are in pixels.~~

#### `screen_list`

| Arg | |
|---|---|
| `limit` | optional integer: default 20, maximum 50 |

- Only usable screens, sorted yours first, then unlinked screens by the smaller distance to the user or agent; other dimensions last. `truncated` counts rows omitted by `limit`. See the [station screen-tool decision](stations-plan.md#screen-tools).
- ~~The description includes: "`distance` is how far each screen is from the user and from you in the layout. It's useful for telling screens apart, e.g. when the user says 'over here' or 'by you'."~~

```jsonc
{
  "you": { "station": { "id": "st_…", "name": "desk" }, "project": "prj_…" },  // station null if none
  "screens": [{
    "id": "550e8400-e29b-41d4-a716-446655440000", "name": "whiteboard",
    "url": "https://…", "title": "Excalidraw",                       // url "" when idle
    "viewport": [1504, 915],                                          // page size in pixels
    "station": { "id": "st_…", "name": "desk" },                   // null if unlinked
    "yours": true,
    "openedByYou": true,
    "available": true,                                                // false if not currently running for the user
    "lease": { "thread": "thr_…", "secondsLeft": 12 },                // null if free
    "distance": { "user": 4, "you": 3 }                               // rounded; you null if your body isn't present
  }],
  "truncated": 0
}
```

#### `screen_open`

Shows something on a screen.

| Arg | |
|---|---|
| `screen` | optional, id or name |
| `kind` | `url` · `file` |
| `target` | URL · file path |
| `name` | optional: names the screen |

- ~~It waits for the page to load (up to about 15s), then returns `{ screen, url, title }`.~~
- ~~It sets `openedBy` to the caller.~~
- ~~Names tell agents at a glance what each screen is for. The description says: rename a screen when its name no longer describes what you're now using it for; otherwise leave the name alone.~~
- `terminal` and `review` kinds are planned; see Later.

#### `screen_cdp`

A plain CDP passthrough to the screen's page.

| Arg | |
|---|---|
| `screen` | optional, id or name |
| `method` | e.g. `Runtime.evaluate`, `Network.getResponseBody` |
| `params` | optional object |

- ~~The description names the common call: `Runtime.evaluate { expression, awaitPromise: true, returnByValue: true }` for running JS.~~
- ~~It returns the method's result plus the current `{ url, title }`.~~
- ~~Results are capped at about 64 KB. An oversized `Page.captureScreenshot` result points to `screen_screenshot`.~~
- ~~One call, one result. Events aren't streamed. Timeout about 30s.~~
- ~~It fails with `unavailable` on an idle screen.~~

#### `screen_screenshot`

| Arg | |
|---|---|
| `screen` | optional, id or name |

- ~~It takes the frame already painted for the screen, at its viewport size.~~
- ~~Like `minecraft_pov`, it saves the PNG to the thread's Attachments folder and returns `{ path, url, title }` with "open this with your image tool".~~
- ~~It doesn't need the lease, since it only reads.~~
- ~~It fails with `unavailable` on an idle screen.~~

#### `screen_close`

| Arg | |
|---|---|
| `screen` | optional, id or name |

- ~~It closes the browser and clears `url` and `openedBy`, so the screen goes idle: dark surface, bar showing its name.~~
- ~~The record and the station link stay, and a later `screen_open` brings it back.~~
- ~~Players get the same thing as **Turn off (clear page)** in the bar menu.~~

### Agent instructions

~~Every agent in the mod is told about screens, the way Codex or Claude Code agents know about their built-in browser: in the standing instructions (`surface/agents.md`) and the `tma-agents` skill. Briefly: what screens are for (showing the user something, working on a shared page), that `screen_list {}` lists usable screens with yours first, how `distance` helps pick the one the user means, and that a `leased` screen is someone else's for a few seconds.~~

### Lease

- ~~`screen_open`, `screen_cdp` and `screen_close` take the screen's lease, or renew it if you already hold it. The lease lasts **20s after your last call ends**, and is also held while a call is running. `screen_close` releases it afterwards.~~
- ~~While someone else holds it, other threads' calls fail with `leased: { thread, secondsLeft, url }`. Agents decide whether to wait.~~
- ~~Players are never blocked, and `screen_list` and `screen_screenshot` do not require a lease but still enforce station access.~~
- ~~The lease is released early when the holder's thread stops or is archived, or when the screen breaks or is closed.~~
- ~~It lives in the game's memory only and is never saved.~~
- ~~While leased, the screen's bar shows the holder's name.~~

### Errors

~~None of these are retried automatically:~~

| Code | When |
|---|---|
| `choose_screen` | Omitted screen and no single default, includes the candidates |
| `not_found` / `no_access` | Unknown id/name, or a screen linked to a station you do not occupy |
| `leased` | Another thread holds it; includes `{ thread, secondsLeft, url }` |
| `unavailable` | Idle, or not loaded on the user's client (out of range, game closed, world not trusted) |
| `too_large` | Result over the cap |
| `timeout` | No answer in time; the outcome is unknown, so check state before acting again |

### Plumbing

- ~~The tool definitions go in `surface/tools.json` without the `minecraft` flag.~~
- ~~Calls go from the plugin to Java over the existing connection, then onto the client thread. `screen_cdp` uses the screen's `CefDevToolsClient`, and `screen_screenshot` reads its painted frame.~~
- ~~`screen_cdp` calls `browser.getDevToolsClient().executeDevToolsMethod(method, params)`, in-process with no debugging port. Verified live on macOS (jcefmaven 152, off-screen): evaluate and awaited promises return values, page exceptions come back as normal results with `exceptionDetails` (only protocol errors fail the call), the client survives navigation and renderer crash/reload, large results (8 MiB) and console events work.~~
- ~~A call pending when the browser closes never completes. Every call gets our own timeout, and closing a screen fails its pending calls.~~
- ~~Results and events arrive on Minecraft's render thread. Never block there; hand results off.~~
- ~~Chromium's browser process runs inside Minecraft's process (libcef via JNI), so a bug there kills the game without saving. Keep every CEF call on its proper thread, do no blocking work in callbacks, and handle shutdown order carefully.~~
- ~~Screenshots reuse `minecraft_pov`'s save path in `bb-plugin/images.ts`.~~
- ~~The game and its world records own leases, access checks and `openedBy`.~~
- ~~The record syncs to the client as the entity's own synced data; no custom packet. Input targets the entity hit instead of a block hit.~~
- ~~To check while building: a screen with an empty URL must create no browser. I believe the current code still creates one for a blank screen.~~


## Later

- Figure out some way to handle URLs in world downloads as a potential security problem?
- Sign-ins through opt-in cookie import (untested).
- Make survey mode more obvious, so station areas and screen links are easy to see. Ideas: holding a screen item highlights the station it would link to; the bar shows the station and its agent.
- Consider blocking file reads outside project folders, with one rule in onBeforeBrowse (screens run outside agents' sandboxes, so file:// could read what a sandbox denies).
- Floor and ceiling screens (a war table in front of an agent): the same placement and support rules on horizontal faces.
- Windows and Linux testing.
- Per-screen resolution control, if needed. Now fixed: 256 px per block, scaled down to at most 1920x1080 worth of pixels.
- Playtest the 8-block reach for page input to make sure it feels good.
- Page audio.
- Viewing a screen from BB.
- Remote agents reaching screens.
- Crash safety. Renderer crashes are survived (the screen reloads), but a crash in Chromium's browser process takes Minecraft down with no save. Options, roughly in order:
  - Stress test for evidence: many screens, rapid open/close, repeated renderer crash/reload, navigation storms.
  - Safe mode: if Minecraft crashed inside libcef, the next launch starts with screens off, with a toggle to turn them back on. One crash, not a crash loop.
  - Investigate moving Chromium into a separate helper process that streams frames back through shared memory, with input and CDP over IPC. A crash then only blanks the screens, which restart. Big: frame transport alone is around 300 MB/s per active 6x4 screen at 60fps. It would also give remote agents reaching screens a natural shape.
- `terminal` and `review` kinds for `screen_open`:
  - `terminal`: target is a BB terminal id. xterm.js bridged to the BB terminal (output since a sequence number, send).
  - `review`: target is an optional base ref (default: merge base with the main branch). A diff of the caller's environment. Its page design needs its own pass.
  - Both are built-in pages under our own `tma://` scheme, handled inside CEF with no network port.
  - Archiving a thread closes every screen whose `openedBy` is that thread and whose URL is a `tma://` page. Ordinary URL and file pages stay. Unarchiving reopens nothing.
  - When a BB terminal ends on its own, its page shows "terminal closed" and keeps its last output. It isn't closed automatically.
