# Screens

A screen is a window onto a URL that you place in the world. Task boards, dashboards, whiteboards, VNC clients, staging sites and agents' HTML visualizations are all just pages. The mod renders pages and never needs to know what is on them.

You place a screen and ask an agent to set something up on it. You and agents use the same live page: the agent drives it over CDP, and you watch or take over in-game.

## Decided

### Screens in the world

- Breaking a block drops a plain block. A non-rectangle goes dark but keeps its record; it reloads once it is a rectangle again. Breaking the last block deletes the record. No move semantics: rebuilt elsewhere is a new screen.
- Split: the bigger piece keeps the record (tie: the piece with the old bottom-left). Other pieces become new blank screens.
- Merge: never. A right click that would join two screens is refused. Blank blocks join the screen they touch. As a hard rule for every other way of placing (/fill, /setblock, structures, other mods), the shape check after placement pops off the blank blocks that join two screens, as items. Pistons can't move screen blocks.
- Duplicate ids (/clone): the record remembers where its screen is. A group forming elsewhere with an id already in use, or an id with no record, gets a new blank one.

### Screen record

The screen's id, name, URL and `openedBy` are saved in the mod's world state:

```java
record Screen(String id, String name, String url, String openedBy) {}
```

- Blocks store only the screen id.
- `url` is empty when the screen is idle, meaning no Chromium browser exists for it.
- `openedBy` is the thread that last opened the page. It's set by `screen_open`, and cleared when a player navigates or the screen is closed.
- The page kind is in the URL: built-in pages use `tma://terminal/…` and `tma://review?…`.

### Access and defaults

- **Which screens an agent can use:** screens in its project's box, plus shared screens (outside any project box). Never another project's screens.
- **"Yours":** a screen linked to your station. Links are worked out live: a screen belongs to the nearest station within about 4 blocks.
- **When `screen` is omitted:**
  - If your station has exactly one screen, that's the one used.
  - Otherwise the call fails with `choose_screen` and returns only your station's screens.
  - Agents without a station always pass `screen`.
- **`screen` arguments** accept an id (`s4`) or a name (`whiteboard`).

### Tools

All five are ungated, so every mod agent gets them, with or without Minecraft access. Responses avoid Minecraft details: threads are identified by id, and sizes are in pixels.

#### `screen_list`

| Arg | |
|---|---|
| `mine` | optional boolean: only your station's screens |

- Without `mine`: every screen you can use, sorted yours first, then nearest the user, capped at 50, with `truncated: n` when there are more.
- The description includes: "`distance` is how far each screen is from the user and from you in the layout. It's useful for telling screens apart, e.g. when the user says 'over here' or 'by you'."

```jsonc
{
  "you": { "station": { "id": "st_…", "label": "desk" }, "project": "prj_…" },  // station null if none
  "screens": [{
    "id": "s4", "name": "whiteboard",
    "url": "https://…", "title": "Excalidraw",                       // url "" when idle
    "viewport": [1504, 915],                                          // page size in pixels
    "project": "prj_…",                                               // null = shared
    "station": { "id": "st_…", "label": "desk", "thread": "thr_…" },  // null if unlinked; thread null if the station is free
    "yours": true,
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
| `kind` | `url` · `file` · `terminal` · `review` |
| `target` | URL · file path · BB terminal id · `review`: optional base ref (default: merge base with the main branch) |

- It waits for the page to load (up to about 15s), then returns `{ screen, url, title }`.
- It sets `openedBy` to the caller.
- **Phase 1:** `url` and `file`. **Phase 2:** `terminal` (xterm.js bridged to a BB terminal) and `review` (a diff of the caller's environment), both served as built-in pages through a custom CEF scheme with no network port.

#### `screen_cdp`

A plain CDP passthrough to the screen's page.

| Arg | |
|---|---|
| `screen` | optional, id or name |
| `method` | e.g. `Runtime.evaluate`, `Network.getResponseBody` |
| `params` | optional object |

- The description names the common call: `Runtime.evaluate { expression, awaitPromise: true, returnByValue: true }` for running JS.
- It returns the method's result plus the current `{ url, title }`.
- Results are capped at about 64 KB. An oversized `Page.captureScreenshot` result points to `screen_screenshot`.
- One call, one result. Events aren't streamed. Timeout about 30s.
- It fails with `unavailable` on an idle screen.

#### `screen_screenshot`

| Arg | |
|---|---|
| `screen` | optional, id or name |

- It takes the frame already painted for the screen, at its viewport size.
- Like `minecraft_pov`, it saves the PNG to the thread's Attachments folder and returns `{ path, url, title }` with "open this with your image tool".
- It doesn't need the lease, since it only reads.
- It fails with `unavailable` on an idle screen.

#### `screen_close`

| Arg | |
|---|---|
| `screen` | optional, id or name |

- It closes the browser and clears `url` and `openedBy`, so the screen goes idle: dark surface, bar showing its id and name.
- The record and the station link stay, and a later `screen_open` brings it back.
- Players get the same thing as a power button on the bar.

### Lease

- `screen_open`, `screen_cdp` and `screen_close` take the screen's lease, or renew it if you already hold it. The lease lasts **20s after your last call ends**, and is also held while a call is running. `screen_close` releases it afterwards.
- While someone else holds it, other threads' calls fail with `leased: { thread, secondsLeft, url }`. Agents decide whether to wait.
- Players are never blocked, and `screen_list` and `screen_screenshot` always work.
- The lease is released early when the holder's thread stops or is archived, or when the screen breaks, goes dark or is closed.
- It lives in the game's memory only and is never saved.
- While leased, the screen's bar shows the holder's name.

### Thread lifecycle

- **Archiving a thread** closes every screen whose `openedBy` is that thread and whose URL is a `tma://` page (terminal, review). Ordinary URL and file pages stay. The thread's leases are released. Unarchiving reopens nothing.
- **When a BB terminal ends on its own,** its page shows "terminal closed" and keeps its last output on screen. It isn't closed automatically.

### Errors

None of these are retried automatically:

| Code | When |
|---|---|
| `choose_screen` | Omitted screen and no single default; includes your station's screens |
| `not_found` / `no_access` | Unknown id/name, or another project's screen |
| `leased` | Another thread holds it; includes `{ thread, secondsLeft, url }` |
| `unavailable` | Idle, or not loaded on the user's client (out of range, game closed, world not trusted) |
| `too_large` | Result over the cap |
| `timeout` | No answer in time; the outcome is unknown, so check state before acting again |

### Plumbing

- The tool definitions go in `surface/tools.json` without the `minecraft` flag.
- Calls go from the plugin to Java over the existing connection, then onto the client thread. `screen_cdp` uses the screen's `CefDevToolsClient`, and `screen_screenshot` reads its painted frame.
- `screen_cdp` calls `browser.getDevToolsClient().executeDevToolsMethod(method, params)`, in-process with no debugging port. Verified live on macOS (jcefmaven 152, off-screen): evaluate and awaited promises return values, page exceptions come back as normal results with `exceptionDetails` (only protocol errors fail the call), the client survives navigation and renderer crash/reload, large results (8 MiB) and console events work.
- A call pending when the browser closes never completes. Every call gets our own timeout, and closing a screen fails its pending calls.
- Results and events arrive on Minecraft's render thread. Never block there; hand results off.
- Chromium's browser process runs inside Minecraft's process (libcef via JNI), so a bug there kills the game without saving. Keep every CEF call on its proper thread, do no blocking work in callbacks, and handle shutdown order carefully.
- Screenshots reuse `minecraft_pov`'s save path in `bb-plugin/images.ts`.
- The game and its world records own leases, access checks and `openedBy`.
- To check while building: a screen with an empty URL must create no browser. I believe the current code still creates one for a blank screen.

## Later

- Dyeable screen frames (screen + dye), to tell project screens apart.
- Sign-ins through opt-in cookie import (untested).
- Make survey mode more obvious, so stations, project boxes and screen links are easy to see. Ideas: holding a screen item highlights the station it would link to; survey mode draws screen-to-station links in the station's color; the bar shows the station and its agent.
- Consider blocking file reads outside project folders, with one rule in onBeforeBrowse (screens run outside agents' sandboxes, so file:// could read what a sandbox denies).
- Floor and ceiling screens (a war table in front of an agent).
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
