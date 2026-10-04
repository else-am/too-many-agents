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
- Your installed, running BB 0.45.0 or newer.
- Node.js and npm to build the Minecraft TypeScript plugin.
- At least one signed-in coding agent supported by BB, such as Codex or Claude Code.

The TypeScript plugin in `bb-plugin/` uses the BB plugin SDK. BB remains your
existing external dependency: these tools never download, install, start, stop,
or update it. `scratch/bb/` is reference material and never a build or runtime
input. Developed on macOS; `tools/dev` requires macOS or Linux.

## Build and run

```sh
tools/bb                                 # build our plugin; install or reload it in your running BB
tools/build build
tools/build runClient -PdevWorld          # isolated flat test world
```

`tools/build` uses a Java 21 `JAVA_HOME`, or finds a JDK in common locations.
The mod JAR is `build/libs/too-many-agents-<version>.jar`.

To use your own NeoForge installation, copy only that JAR into its `mods/`
folder.

Any game finds BB by itself through the address BB records in
`~/.bb/bb-app-runtime.json`; running games reconnect within a few seconds. Restart Minecraft after replacing an installed JAR or
changing physical tools in `surface/`. After editing plugin code or its prompts,
run `tools/bb` again.

`tools/bb` finds `bb` on PATH, or the CLI bundled with the installed macOS BB app.
Use `--bb-cli /path/to/bb` for another installed CLI.

The plugin's route accepts only local, non-browser JSON requests, as BB's own API
does. The mod's physical API has a private `<game dir>/too-many-agents/connection.json`
with its token; never share it.

## Using it

Create a body in Minecraft and choose a native BB project, provider and model.
Each body belongs to its world's stable ID and one BB thread. Conversations,
queued messages, provider settings and archives live in BB. Minecraft saves
agents, their BB thread links, body settings, inventories, project bounds and
stations with the world.

New agents belong to **This world** unless you choose another project. Each world
gets a BB project ("Minecraft: <save name>") the first time it is used, and its
agents share one folder inside the save, `too-many-agents/workspace/`, so their
files travel with the world. Agents with **No project** use BB's Personal project.

A separate world copy keeps its bodies and workspace files; its agents start new
conversations in the copy's own project.

Project bounds and stations keep bodies within their assigned space. Press B
for survey mode to see and draw boxes. Idle behaviors are cosmetic and never
change the world. Agents with Minecraft access receive the plugin's Minecraft
prompts and installed physical tools; other threads continue ordinary project
work in BB.

Chat renders Markdown natively, including tables, lists, quotes, inline code and
code blocks. Drag to select text; code and table headers have Copy controls.
Wide code and tables scroll horizontally with a trackpad or Shift + wheel.
Code uses Pixel Code at its native 9-pixel size with oversampling disabled,
keeping glyph edges aligned with Minecraft's pixel grid.
Web links follow Minecraft's chat-link settings. Workspace and thread-storage
file links open in BB, including `path:line` and `path#Lline` links.

Images load through BB's attachment/file APIs or public HTTP(S) URLs. Click an
image to zoom and pan; GIF and WebP animations show their first frame. Images
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
tools/dev --test                         # hot reload in the guarded run/ test world
tools/dev                                # personal development game in run/play/
```

Both first run `tools/bb`, so BB always has the current plugin.

`tools/dev` needs a JetBrains Runtime 21 with enhanced HotSwap; it finds Android
Studio or IntelliJ's bundled runtime, or uses `TMA_DEV_JAVA_HOME`. Saving supported
Java edits compiles and reloads them. Resources, surface prompts, mixins, build
files and live callback owners require a restart; the terminal reports this.
`tools/Play.command` opens the personal development game on macOS.

```sh
python3 tools/agents.py --game-dir run list
python3 tools/agents.py --game-dir run ui widgets
python3 tools/too_many_agents.py --game-dir run state
python3 tools/smoke.py run --command
python3 tools/bb-smoke.py --model gpt-6.1-sol --children
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
