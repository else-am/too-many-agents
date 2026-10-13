> [!WARNING]
> Super alpha, expect hella breaking changes and general confusion

<p align="center"><img src="docs/logo-3d.png" width="200" alt=""></p>

# Too Many Agents

A Minecraft mod that gives coding agents NPC bodies in a singleplayer world.
[BB](https://github.com/get-bb/bb) owns projects, threads, providers, permissions,
worktrees and conversation history. The mod gives those threads Minecraft bodies
and physical tools. Current support is a physical singleplayer client with an
integrated server.

## Requirements

- Minecraft 1.21.1 with NeoForge 21.1.251+, singleplayer.
- Java 21 to build the mod.
- Your installed, running BB 0.46.0 or newer.
- Node.js and npm to build the bundled Minecraft TypeScript plugin; players do not need them.
- At least one signed-in coding agent supported by BB, such as Codex or Claude Code.

The TypeScript plugin in `bb-plugin/` uses the BB plugin SDK. BB remains your
existing external dependency: these tools never download, install, start, stop,
or update it. `scratch/bb/` is reference material and never a build or runtime
input. Developed on macOS; `tools/dev` requires macOS or Linux.

## Build and run

```sh
tools/build build
tools/build runClient -PdevWorld          # isolated flat test world
```

`tools/build` uses a Java 21 `JAVA_HOME`, or finds a JDK in common locations.
The mod JAR is `build/libs/too-many-agents-<version>.jar`.

To use your own NeoForge installation, copy only that JAR into its `mods/`
folder. The JAR includes its matching BB plugin. Open the agent interface (G),
or **Mod settings**. BB connection appears at the top; opening the agent interface
before setup is complete takes you there automatically. BB is detected for you;
choose an instance only if more than one is found. Choose **Allow plugin installation
and updates** to let the mod manage its Minecraft plugin. Manual location and repair
controls expand inline under **Connection details**. This permission applies only to the selected BB.

The Minecraft plugin publishes its running BB address automatically, and the mod
finds it without environment variables or JVM options, including when BB uses a
custom data directory. Each Minecraft installation remembers its selected BB.
Mod and plugin versions must match exactly. A missing or older plugin can update
automatically after permission; replacing a newer plugin requires confirmation.
Leave all connected Minecraft worlds before replacing the plugin. Games using the
same version may share it. The mod never replaces its own running JAR or manages BB.
For an old or broken plugin that cannot report connected games, close its games
and disable that plugin in BB before restoring the bundled copy. Plugins installed
through another package manager require manual source changes in BB.

Setup preferences live in `<game dir>/config/too-many-agents-bb.json`; extracted
plugin releases live in `~/.too-many-agents/plugins/`. BB installs them through its
CLI, preserving plugin settings when moving between local releases.
Running games reconnect within a few seconds. Restart Minecraft after replacing an installed JAR or
changing physical tools in `surface/`. After editing plugin code or its prompts,
restart development, or run `tools/build packageBbPlugin` and choose **Reload plugin**
under mod settings → Connection details.

The shared release version is `bb-plugin/package.json`'s `version`; Gradle reads
it and packages the built plugin and its runtime dependencies with the mod.

The plugin's route accepts only local, non-browser JSON requests, as BB's own API
does. The mod's physical API has a private `<game dir>/too-many-agents/connection.json`
with its token; never share it.

## Using it

Create a body in Minecraft and choose a native BB project, provider and model.
Each body belongs to its world's stable ID, one BB instance and one BB thread.
Conversations, queued messages and provider settings live in BB. Minecraft saves
agents, their BB ownership and thread links, body settings, inventories, stations
and project blocks with the world. Only the selected BB is connected. Switching
BB leaves the other instance's agents disconnected; switching back reconnects them.

New agents belong to **This world** unless you choose another project. Each world
gets a BB project ("Minecraft: <save name>") the first time it is used, and its
agents share one folder inside the save, `too-many-agents/workspace/`, so their
files travel with the world. Agents with **No project** use BB's Personal project.

A separate world copy keeps its bodies and workspace files; its agents start new
conversations in the copy's own project.

Access comes from the agent's project. **This world** agents have physical tools
and no station or confinement. Agents in other BB projects do coding work and
can optionally occupy a station; both kinds can use screens.

A **station** is a physical block with a project, instructions, body choice and
optional execution settings. Right-click to configure it or **Spawn agent here**.
Blank execution fields inherit BB's project defaults; set a field to pin it.
Station instructions precede the first task. Execution, instructions and body
changes affect future spawns; area and behavior changes affect the occupant now.
A station needs a project before spawning, and its project cannot change while
occupied. World projects cannot be assigned to station or project blocks.

Stationed agents stay in a square area around the block, except while following.
Radius is 0–49 (width `2r+1`), height 1–99 upwards from the block; defaults are
3×3 and height 3. Press **B** to view named station-area outlines. Survey is
view-only. Break a free station to carry its settings to another location;
occupied stations cannot be broken. Archiving or deleting the occupant frees it;
unarchiving restores the agent without a station.

A **project block** opens project settings and stamps its project onto a station
item through its slot, preserving the item's other settings. It is only a marker:
it defines no area and owns no screens. Breaking it drops a plain project-block
item. A station's copy slot copies its settings onto another station item.

World agents have one behavior: stand (default), wander near the starting point,
or follow. It pauses from the first script or physical action until the turn ends.
Project agents have separate working, wants-you and idle behaviors: stand, wander,
follow, jump, spin or look at the player. A station supplies its occupant's
behaviors; an agent without a station uses its own settings.

Screens intersecting a station's area link to it automatically. Only its occupant
can use them; free stations' screens are off-limits to all agents. Unlinked screens
are shared, and players can use any screen. `screen_list` lists only usable screens,
your station's first, then unlinked screens by proximity to you or the player.
It defaults to 20 rows (maximum 50); `truncated` counts omitted rows.

From an embodied thread, use `bb minecraft spawn --parent-self --prompt 'Task'`
for a child, or omit `--parent-self` for an independent thread. Use
`bb minecraft spawn --help` for BB execution/environment options and Minecraft
body/station options. `bb minecraft bodies` and `bb minecraft stations` describe
the current world. Other coordination uses ordinary `bb thread` commands.
The plugin prepares BB requests for both the native UI and CLI, including
project and environment selection, first messages, and lifecycle changes. The
mod owns physical bodies and saved world records, and renders the plugin’s UI
data. BB integration changes belong in `bb-plugin/`; the mod asks for actions
such as creating an agent or choosing a worktree.

Spawn at a station by its UUID or name; its project is implicit. A conflicting
`--project` is rejected. Project agents spawn only in their own project; world
agents can also spawn into other non-world projects. A station is optional for
project agents and unavailable to world agents. Occupancy begins at spawn and
lasts until archive or deletion.

```sh
bb minecraft stations --json
bb minecraft spawn --parent-self --station desk --prompt 'Fix the issue'
```

Explicit spawn flags override station execution choices, subject to the caller's
permission cap. Without a station, children inherit their parent's live execution
settings; independent spawns use BB project defaults. Worktrees are a spawn-time
choice (`--new-environment worktree`). Existing threads keep their BB settings.

World-agent modes combine physical abilities and command access: `survival` uses
inventory and tools; `creative` grants Creative physical actions; `creative_commands`
also permits world commands using the human's existing permissions. The UI labels
that last choice **Creative + commands**. Children cannot gain abilities their
caller lacks. There is no separate command-access setting.

Minecraft instructions ask agents to use embodied spawning; they do not disable
native subagents or change unrelated BB threads' settings.

Agent messages delivered by BB appear in world chat when **Show agent
communication** is enabled and both bodies are present. BB owns message queues
and communication permissions. Stopping a thread cancels its physical actions;
archiving drops its inventory, saves and despawns its body, and frees its station.
Unarchiving restores the saved body with an empty inventory when the world is
open. A missing or deleted conversation leaves the agent disconnected and retains
its binding. **Archive** in Minecraft hides the agent locally and works while BB
is unavailable. It keeps the binding and saved body; restore it from mod settings
→ Archive. Reconnecting does not undo a local archive or restore choice.

Chat renders Markdown natively, including tables, lists, quotes, inline code and
code blocks. Drag to select text; code blocks and diagrams have Copy controls.
Wide code and tables scroll horizontally with a trackpad or Shift + wheel.
Code uses Pixel Code at its native 9-pixel size with oversampling disabled,
keeping glyph edges aligned with Minecraft's pixel grid.
Web links open directly without a confirmation when Minecraft chat links are
enabled. Workspace and thread-storage file links open in BB, including `path:line`
and `path#Lline` links.

Images load inline through BB's attachment/file APIs or public HTTP(S) URLs,
fitted to their proportions. GIF and WebP animations show their first frame. Images
are limited to 5 MB, 4096 pixels per side and 4 megapixels. Loading and decoding
run off the render thread; closing chat releases its textures.

Closed `mermaid` fences render flowcharts, sequence, state, class, ER and XY
diagrams through a browser-free worker in our BB plugin. Mermaid support is a
subset of the full language; unsupported or malformed diagrams keep their
copyable source and show an error. Diagrams are limited to 20,000 characters,
300 lines and five seconds of rendering. Raw HTML is displayed as text.

## Development

See [AGENTS.md](AGENTS.md) for development rules and packaged-JAR verification,
and [TODO.md](TODO.md) for outstanding work.

```sh
tools/dev --test                         # build and launch the guarded run/ test world
tools/dev                                # personal development game in run/play/
```

Both build the plugin once through Gradle, then Minecraft uses its normal BB setup
to install or reload this checkout before connecting. `tools/build runClient` uses
the same development mode. A same-version plugin is refreshed once per launch;
the current world's BB connection pauses during replacement, then reconnects.
The world stays open and conversations keep running in BB. Other connected games
block replacement. A failed reload waits for **Reload plugin**; background
connection checks do not repeat it. Development does not change your permission
for automatic updates in packaged games. Newer plugins still require explicit
confirmation before a downgrade. Packaged-JAR runs do not enable development mode.

`tools/dev` uses Java 21 through `tools/build`; no special JetBrains runtime is
needed. After changing Java, resources, or the BB plugin, close Minecraft normally
and rerun `tools/dev` (or `tools/dev --test`). Each launch builds the current code;
there is no file watcher, debugger, or live class replacement.
`tools/Play.command` opens the personal development game on macOS.

```sh
python3 tools/agents.py --game-dir run list
python3 tools/agents.py --game-dir run ui widgets
python3 tools/too_many_agents.py --game-dir run state
python3 tools/smoke.py run --command
python3 tools/bb-smoke.py --model gpt-6.1-sol --children
python3 tools/bb-lifecycle-smoke.py --model gpt-6.1-sol
python3 tools/bb-boundary-smoke.py --model gpt-6.1-sol
python3 tools/bb-recovery-smoke.py --agent-id AGENT_ID_FROM_BOUNDARY_CHECK
```

These diagnostics exercise the real mod and native UI. Validate releases with
`tools/build build`, followed separately by
`tools/build runPackagedClient -PpackagedJarRun -PdevWorld`. Game runs, installed npm
dependencies and generated plugin bundles are ignored.
The BB safety fixture requires an exclusive game window in the guarded `run/`
test world. It probes physical callback boundaries and world switching; it never
stops BB. The fixture is retained for persistence inspection.

In the guarded test world, `ui dev_drop --json '{"paths":["/absolute/test/image.png"]}'`
exercises native file drops; files must be inside this checkout's `run/` or `scratch/`.

Rich-chat acceptance uses real native event handlers and the installed BB plugin:

```sh
uv run --with pillow python tools/rich_chat_fixtures.py
python3 tools/rich_chat_acceptance.py --game-dir run --assets
```

Run from a BB thread whose workspace is this checkout, or supply `--thread-id`.
The harness uses the guarded development world, uploads a small test attachment
to that thread's project, and exercises parsing, streaming, copying, image
formats/failures, diagrams, native viewing, and texture cleanup. It starts no
agent turns. Inspect the fixture screenshots as well as the assertions.

## License

[MIT](LICENSE). BB remains an external installed runtime; its dependencies are
not bundled into the Minecraft mod JAR.

The JAR bundles CommonMark Java (BSD-2-Clause), the autolink library (MIT),
TwelveMonkeys ImageIO (BSD-3-Clause), and [Pixel Code 2.2](https://github.com/qwerasd205/PixelCode/tree/v2.2) (OFL-1.1).
The plugin uses beautiful-mermaid (MIT) and resvg (MPL-2.0). Their license files remain
in the bundled JARs, npm packages, and font asset directories.
