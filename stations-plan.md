# Stations

## Implementation checkpoints

- [x] ~~Physical station/project blocks, carried settings, and persistent index.~~
- [x] ~~Station occupancy, project-derived access, spawning, and execution defaults.~~
- [x] ~~Station scripting, world/project behaviors, and confinement.~~
- [x] ~~Screen linking, access, and listing.~~
- [x] ~~Pointing context.~~
- [x] ~~Station/project block screens, item slots, settings, and survey UI.~~
- [x] ~~Remove obsolete roles, boxes, commands, settings, and documentation.~~
- [x] ~~Live acceptance, packaged JAR checks, and persistence after reopening.~~

A station is a block you place in the world. A project agent works at a station: it stays inside the station's area, uses the station's screens, and starts with the station's instructions and settings. You can pick a station up with its settings intact and place it somewhere else, the way you can with a shulker box.

Station blocks replace drawn station boxes, project boxes, roles and the per-agent Minecraft access setting. Survey mode stays only to show station areas. A project becomes a BB workspace binding with no shape in the world.

## Decided

- Prefer discarding obsolete mod data over adding migration or compatibility code. Preserve worlds and unrelated BB data.

### Two kinds of agents

| | World agents | Project agents |
|---|---|---|
| Project | This world's world project, the automatic catch-all | Any other BB project, except other worlds' world projects |
| Minecraft access | Always: `minecraft_run`, `minecraft_settings`, `minecraft_pov`, the scripting skill | Never |
| Screens | Yes | Yes |
| Stations | Never | Optional |
| Behavior | One `behavior`: `stand`, `wander` or `follow` | Per status (`working`, `wants_you`, `idle`) |
| Confinement | None | The station's area, when stationed |
| Game mode | `survival` / `creative` / `creative_commands` | Not applicable |

- Access comes from the project. The per-agent "Minecraft access" setting, its toggle, `--minecraft-access` and the stored `minecraftAccess` field are all removed.
- Children get access from their project, as everyone else does.
- **Spawning:** project agents spawn only into their own project, as now. World agents can spawn into their own world project, or into any other project except a world project, with or without `--station`. This replaces the single own-project check in `AgentService.createBody`. `--parent-self` still works across projects: BB allows a parent in another project, and the parent receives the child's results.
- No migration: existing project agents with access on lose it.

### The block

- A station is a block with a block entity, placed and broken like any other block. Players and agents can both break a free one. An occupied station can't be broken, by anyone and in any game mode, until its agent is archived.
- Pistons can't move stations, and explosions can't destroy them.
- Breaking a station drops an item that carries its settings (see [Station data](#station-data)). Placing that item creates a new station with the same settings.
- A freshly crafted station has no project, and it's inert (no spawning) until one is set through its screen or a project block.
- No migration. Existing `stations` and `bounds` in `world.json` are dropped on load.
- Station and project blocks both get survival recipes and creative-tab entries. For now the recipes and textures are placeholders, to be designed together with the blocks' look.

### Area and confinement

- Each station has an **area** set by two numbers, and it replaces today's station box:
  - **radius:** 0–49, a square centered on the block in x/z, shown as its width `2r+1` (1×1, 3×3, … 99×99). 0 is the block's own column, and 1 reaches one block out on each side.
  - **height:** 1–99, counted up from the block's own level. As today, standing on the top layer counts, so radius 0 and height 1 means the agent stands on the block.
- **Default:** radius 1 (3×3), height 3.
- Confinement works as it does now: the body stays inside, teleports back after a short walk if it gets too far out, and only leaves while following.
- Only stationed project agents are confined. World agents, and project agents without a station, are unconfined.
- `WorldState.box(agentId, projectId, dimension)` becomes `box(agentId, dimension)`: the station's area, or null.

### Instructions

- A station can carry custom instructions.
- For an agent spawned at a station, they go at the start of its first message, where role instructions go today.

### Execution settings (no roles or presets)

- **Optional fields on a station:** `providerId`, `model`, `reasoningLevel`, `serviceTier` and `permissionMode`. A blank field inherits what BB would pick for a new thread in that project: its remembered project defaults, then the provider's default model. Most stations leave everything blank, so a new default in BB reaches them with no edits.
- **Pinning:** set a field only for a station that should differ, like a reviewer desk running a different model.
- **Bulk changes:** change BB's defaults for unpinned stations. For pinned ones, edit them individually, or ask a world agent to loop over `bot.stations()` with `configureStation`. If that gets tedious, a later "set for all stations" button could go in the project block's screen.
- **Caller cap:** a station's pinned `permissionMode` still can't exceed the spawning agent's own mode, as now. Players aren't capped.
- **Existing agents** keep their BB thread settings, which BB owns. Station edits apply to future spawns.
- **Loose spawns:** children inherit their parent's live execution settings, as BB already does. Your own spawns start from BB's project defaults, and explicit pickers or flags override them. If switching between combinations gets annoying, a later "Copy settings from…" an existing agent could reuse `roleCapture`'s thread reading.
- **Remembered choices:** the in-game spawn draft is pre-filled from BB's project defaults, as now, and the provider, model, reasoning, tier and approvals you pick become the next defaults, the same as in BB's composer.
  - The SDK only reads these defaults (`projects.defaultExecutionOptions`). It's unverified whether threads created through `threads.create` update them. Check with a test spawn.
  - If they don't, keep a per-project "last used" record in plugin storage and fill drafts from it before falling back to BB. Stations don't use it.
- **Worktrees** are a spawn-time choice (`--new-environment worktree`, or the spawn UI), not a station field.
- **Instruction order at spawn:** station instructions, then the task.
- **Roles are removed** entirely, with no replacement store.

### Behaviors

**Project agents** keep today's per-status behaviors: `working`, `wants_you` and `idle`, each one of `stand`, `wander`, `follow`, `jump`, `spin` or `look` (at the player). Block targets, for looking at a block and for `swing`, are removed, since absolute coordinates break when a station is copied.
- While stationed, the station's `behaviors` apply.
- Otherwise the agent's own `behaviors` apply, set in agent settings.
- Project agents never act in the world, so their behaviors don't need to pause.

**World agents** have one `behavior` that doesn't depend on status: `stand`, `wander` or `follow`.
- The default is `stand`. Wander stays near where the behavior started, since there's no area.
- It's set by the user in agent settings, or by the agent through a new `minecraft_behavior` tool.
- It pauses from the agent's first `minecraft_run` script or physical action in a turn until that turn ends. Turns that never touch the world don't pause it.
- The hook: `claimScript`, and starting an action, set a held flag on the controller, and the behavior tick is skipped while the flag is set. The flag clears when the body's synced `activity` leaves `working`.

### Screens

- A station's linked screens are the screens with any part inside its area, worked out live as linking is today. Nothing is stored, and there's no manual linking: to unlink a screen, move it or shrink the area. Survey mode shows exactly which screens are linked.
- A screen links to at most one station. If it touches several areas, it goes to the station nearest its center, with ties going to the lower id.
- For an agent that should use a screen outside its area, say so in the station's instructions.
- **Access:** a station's linked screens are exclusive to its occupant. A free station's screens are off-limits to every agent: to set one up, spawn an agent at the station and have it open the page. Every other screen is open to any agent, and players can always use any screen.

### Screen tools

- **When `screen` is omitted** (as now): a station with exactly one linked screen uses it. With several, the call fails with `choose_screen` and lists them. With none, or with no station, `screen` is required.
- **`screen_list` ordering:**
  1. The caller's station screens.
  2. Unlinked screens, ordered by the smaller of their distances to the user and the agent. Screens in another dimension sort last.
- **Only usable screens are listed.** Screens linked to another station, occupied or free, are left out. Naming one by UUID or name fails with `no_access`, and the error names its station.
- **No radius.** A `limit` argument (default 20, max 50) caps the rows, and `truncated` says when more exist. Agents raise it when they don't find what they need.
- **Rows:**
  - add `openedByYou`: the screen's `openedBy` is the caller's thread, so an agent can find pages it opened in earlier turns.
  - `yours` means linked to the caller's station.
  - drop `project`. Projects no longer own screens.
- **Arguments:** drop `mine`, since the caller's station screens already come first and are marked `yours`.
- **`you` block:** `{ station, project }`.
- **Pointing context** goes to every agent the user messages, project agents included. When the user is looking at a screen, `ClientControls.capturePointing` reports `{ type: "screen", id, name }` instead of a plain entity, so "this screen" resolves without a `screen_list` call.
- **`agents.md` gets one line on the rule:** "A screen linked to another agent's station is theirs."

### Projects

- Stations and project blocks can never be set to a world project, whether this world's or another's. World projects need no project block or settings, and never appear in station, project-block or spawn project pickers.
- A station belongs to a project. Project boxes are removed entirely, along with their effects: confinement, the rule that stations must sit inside the box, screen privacy, and blocking project removal.
- Removing a project is still blocked by its bodies, but not by its stations. Its stations stay in the world with no project and are inert, like a freshly crafted one. They're always free by then, because the bodies are gone.

### Project block

- **The item:** stackable and stateless. Breaking a placed block drops the plain item.
- **The placed block:** stores one `projectId` and is tinted the project's color. A blank one asks for a project (choose or create) when right-clicked.
- **Its screen:**
  - project settings: the existing `ProjectScreen` (color, folders, …)
  - a 1→1 slot: a station item in, the same station item out with this block's project. It works on blank stations and on stations that already have a project, and keeps every other setting.
- **Only a marker.** It confines nothing, owns no screens, and nothing reads its position. That keeps project boxes' hidden spatial rules from coming back.
- **Changing it:** a placed block's project can be changed from its screen. Several blocks can share a project, and they're all equivalent.
- **World agents** can place one and set any project except a world project (see [Scripting API](#scripting-api-minecraft_run-world-agents)).
- **Removed projects:** their project blocks become blank.

### Survey mode

- The B keybind stays and toggles outlines of every station area, with names. Nothing else: no project boxes, no drawing, no editing.

### Building stations

- World agents create stations by placing the block through `minecraft_run`, and configure them through a new scripting API. They can set any project except a world project.
- `bb minecraft spawn --station <id>` stays.
- **No assigning or unassigning.** An agent gets a station only by being spawned there, with **Spawn agent here** or `--station`, and keeps it until it's archived or deleted. Either releases the station, as archiving does today, and is the only way out. An unarchived agent comes back loose. "Leave the desk for a while" is a station behavior (`follow`), not an unassignment.

### CLI

| Command | |
|---|---|
| `bb minecraft stations` | Keep. Reads the index and returns id, name, position, project, pinned execution and occupant: every station for world agents, and the caller's project's stations for project agents |
| `bb minecraft station assign <id> [--agent] [--unassign]` | Remove. There's no assigning or unassigning |
| `bb minecraft station create/update/delete` | Remove. Stations are made and broken physically |
| `bb minecraft role *` | Remove |
| `bb minecraft spawn --role` | Remove. `--station` applies the station's execution fields, body, behaviors and instructions, and explicit flags still override |
| `bb minecraft spawn --minecraft-access` | Remove. Access comes from the project |
| `bb minecraft spawn --behaviors` | Project agents without a station. World agents use `--behavior stand\|wander\|follow` |
| `bb minecraft spawn --mode` | World agents only |
| `bb minecraft spawn --station <id\|name>` | Implies the station's project. Passing a different `--project` is rejected |

### Scripting API (`minecraft_run`, world agents)

Stations and project blocks work the same way.

- **Placing:** with ordinary block placement. Survival needs the item. Creative can take one from creative items.
- **Reading:** `bot.stations()` and `bot.projectBlocks()` return nearby blocks with their settings.
- **Configuring:**
  - `bot.configureStation(pos, { name, projectId, providerId, model, reasoningLevel, serviceTier, permissionMode, instructions, behaviors, radius, height, body })`
  - `bot.configureProjectBlock(pos, { projectId })`
  - Every field is optional. `projectId` can be any project except a world project, and a freshly placed block stays blank until one is set.
- **Editing an occupied station,** from scripts or the station's screen:
  - Area and behaviors apply to the occupant right away.
  - Instructions only seed the first message, so changes reach future spawns.
  - Execution settings and body only affect future spawns.
  - `projectId` can't change while the station is occupied.

### In-game UI

The station block's screen is new. The other screens only change as the decisions above require.

- **Right-clicking a station opens its screen:**
  - name, project, execution (blank = BB default), instructions, behaviors (per status)
  - area size (shown as width, e.g. 3×3) and height, with a live outline
  - body type
  - its linked screens (read-only)
  - the occupant, or **Spawn agent here** when it's free. That opens the ordinary new-agent chat draft, unchanged, with the station's project and settings filled in. You type the task and send.
  - a 1→1 copy slot: any station item in, a copy of this station's settings out (not its id or occupant). Copying consumes an item, so it works in survival too.
  - **While occupied,** editing follows the [scripting rules](#scripting-api-minecraft_run-world-agents): the project picker is disabled, and execution, body and instructions are labelled "next spawn".
- **Area outlines** show in survey mode (B), and a station's own outline shows while its screen is open.
- **`ProjectScreen`** loses the box row, and its survey button becomes "Show station areas (B)". Its station list stays and reads from the index. It's never offered for the world project, which has no settings.
- **`AgentSettingsScreen`** (existing agents and new-agent drafts):
  - The role picker, **Save a role**, and the role editor are removed.
  - The Minecraft access toggle is removed. In a draft, the controls follow the chosen project.
  - World agents show one Stand/Wander/Follow control and Game mode.
  - Project agents show per-status behaviors. A stationed agent shows "At <station>" with a link to open it, and its behavior rows read "Set by <station>". The station picker is removed.

## Station data

```java
record Station(
    String id,            // UUID, new per placement; pickup ends the identity (as with screens)
    String name,          // unique, 1-32 chars, see Names
    String projectId,     // BB project, never a world project; "" = inert
    String providerId,    // execution fields: optional, "" = BB default
    String model,
    String reasoningLevel,
    String serviceTier,
    String permissionMode,
    String instructions,  // optional, up to 64000 chars
    JsonObject behaviors, // per status: working, wants_you, idle
    int radius,           // 0-49; square in x/z, width 2r+1, centered on the block
    int height,           // 1-99, up from the block's level
    String body,          // entity type, e.g. minecraft:fox; "" = random, as now
    String agentId        // occupant, "" when free
) {}
```

| Field | Saved on the block | Carried by the item | Notes |
|---|---|---|---|
| `id` | ✓ | – | A fresh one on every placement |
| `name`, `projectId`, the five execution fields, `instructions`, `behaviors`, `radius`, `height`, `body` | ✓ | ✓ | Copying a station copies these |
| `agentId` | ✓ | – | An occupied station can't be broken |

- **Index:** a `StationIndex` (`SavedData`, modeled on `ScreenIndex`) records id → dimension, position and a station snapshot, so listing and `--station` work while chunks are unloaded. It replaces the `stations` array in `world.json`. The orphan release on load (`releaseOrphanStations`) moves into the index.
- **Occupancy:** one body per station and one station per body, as now. `station.agentId` is the only record of the link. `stationId` leaves the body settings (`BodySettings.copy`) and stays only as a spawn request argument.
- **Unloaded chunks:** spawning at a station in an unloaded chunk fails.
- **Stations removed another way** (`/setblock`, `/fill`, a world edit): the index drops the station when it notices, as the orphan release does today, and its occupant becomes loose.
- **Names:** stations follow the screens' rules.
  - Names are nonblank, at most 32 characters, and unique across the world, including unloaded chunks, ignoring capitalization.
  - A blank station gets a readable generated name. A name carried by an item that's already taken gets a numeric suffix when placed.
  - Explicit renames to a blank or duplicate name are rejected.
  - `--station` and `configureStation` accept a UUID or a name.
- **Body settings:**
  - World agents store `behavior` and `mode`.
  - Project agents store `behaviors`.
  - `minecraftAccess` is removed, and access is derived as `projectId == worldProjectId`.
- **`world.json`** keeps `id`, `paths`, `bbProjects` and `copiedFrom`, and drops `bounds` and `stations`.

## Removed

**Java**
- `WorldState`: `bounds()`, `projectBox()`, `stations()`, `station()`, `release()`, `requireInsideProject()`, `label()`, `corners()`, the `outsideProject` snapshot flag, and the `bounds`/`stations` arrays in new and copied worlds.
- World commands `bounds-set`, `bounds-clear`, `station-create`, `station-update` and `station-delete`. This includes their routing in `AgentService.projectCommand` and `GameAccess.worldCommand`.
- The `station.edit` callback and `AgentService.editStation`, which become scripting configuration.
- The project-removal preconditions for boxes and stations.
- The project-box fallback in `GameAccess.box` and in idle follow (`GameAccess.idle`).
- `minecraftAccess` as a setting: it leaves `BodySettings.copy`, `validateRoleBody`, the `AgentService.createBody` caller-inheritance logic and the settings UI. `Agent.minecraftAccess` becomes derived.
- `ui/SurveyScreen.java`, and everything in `ui/SurveyMode.java` except toggling and drawing station outlines: corners, editing, project boxes, project filtering and the "outside" label. Removes `ClientControls` `survey_corner`/`survey_edit` and the `SurveyScreen` check. The `survey` toggle and its diagnostics stay.
- `ProjectScreen`'s box row.
- Box limits on physical actions, which only world agents take and world agents are never confined: the `bodyBox`/`box` suppliers and their checks in `AgentHands` (`creative_block_data_outside_box`), `AgentActions` and `ScriptVehicleControls`, and the `box` field in `minecraft_settings` (`GameAccess` settings snapshot).
- In `ScreenTools.Layout`: the box-based `foreign` set, and `LINK_DISTANCE`. Live linking stays, now by area intersection.
- Chat notices ("Notify in chat"): `AgentService` `chat.notice`, `AgentNotifications.chat`, the `notifyInChat` setting and its validation, and the toggle. The notification sound, a client setting, stays.
- Behavior block targets: `look` at a block and `swing` in `validateBehaviors`, `AmbientBehavior` and the behavior rows.
- Role plumbing: `AgentUiAccess.roles/saveRole`, `AgentService.roles/saveRole`, `AgentChatScreen.roleSelection`, the role sections of `AgentSettingsScreen`, and `BodySettings.profile`.

**Plugin**
- `agents.ts`: the `role*` functions (`roleCapture`'s thread reading may come back later for "Copy settings from…"), and `roleInstructions` (it becomes station instructions).
- `game-api.ts`: `role.list` and `role.save`.
- `notifications.ts` (`chatNotifications`) and its registration in `threads.ts`, and `notifyInChat` in `agents.ts` body fields.
- `cli.ts`: `station create/update/delete`, `role *`, `spawn --role`, `spawn --minecraft-access` and `--notify-in-chat`/`--no-notify-in-chat`.

## Changed elsewhere

- **`BodyBox`** stays as the area's world-space box. `BodyBox.of(station)` spans x/z of the block ± `radius`, and y from the block's level to `height - 1` above it.
- **`AgentService.createBody`:** spawning with `stationId` claims the station through the index, and the body spawns inside the station's area. Station instructions are stored with the pending first message. Spawning a world agent at a station is rejected. The `station-assign`/`station-release` world commands become internal claim and release steps for spawning and archiving.
- **World copies:** station blocks copy with the world. Their occupants are the copy's bodies (same agent ids), and the index rebuilds or carries over.
- **`GameAccess.idle`:**
  - For a world agent, it uses the loose `behavior`, skipped while held.
  - For a project agent, it uses the station's `behaviors` when stationed and its own otherwise.
  - `AgentService.validateBehaviors` validates both shapes.
- **`bb-plugin/server.ts`:** Minecraft tools and the scripting skill are gated on the thread's project being the world project, not on `pluginMetadata.minecraftAccess`.
- **`surface/tools.json`:**
  - A new `minecraft_behavior { behavior: "stand" | "wander" | "follow" }` tool (`minecraft: true`).
  - `minecraft_settings` drops "the box you must stay inside", since world agents are never confined.
  - The `screen_list` and `screen` argument descriptions follow the access rule above.
- **`bb-plugin/surface/agents.md`:** replace "Children cannot gain Minecraft … access beyond the caller" with the project rule.

## Docs and tests

- **Docs:**
  - `README.md`: the lines on bounds and stations, survey mode (B, now view-only), roles, Minecraft access, and `station create`.
  - `bb-plugin/skills/minecraft-agents/SKILL.md`: the Stations and Roles sections, `--minecraft-access`, and Notify in chat.
  - `bb-plugin/surface/agents.md`: the screens and access paragraphs.
  - `bb-plugin/skills/minecraft-scripting/SKILL.md`: movement "inside your permitted box".
  - `screens-plan.md`: the Access section is superseded here.
- **Tests and tools:**
  - `tools/bb-smoke.py`: creates and deletes stations over HTTP. It needs a dev hook to place and configure a station block in the test world.
  - `tools/agents.py stations`: reads from the index.
  - `tools/screens_tools_acceptance.py`: covers the new `screen_list` shape. `--foreign-screen` (a screen inside another project's box) becomes a screen linked to another station.
  - `ClientControls`: `ui station` and `ui project_block` open a block's screen by position or name, guarded to the test world like the other dev hooks, so `tools/agents.py` can drive both screens, the copy and project slots, and **Spawn agent here**.
  - Remembered spawn choices: in the test world, spawn with a non-default model and approval mode, then confirm the next spawn draft is pre-filled with them.
