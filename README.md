> [!WARNING]
> Super alpha, expect hella breaking changes and general confusion

<p align="center"><img src="docs/logo-3d.png" width="200" alt=""></p>

# Too Many Agents

A Minecraft mod that gives coding agents NPC bodies in a singleplayer world.
Agents (Codex or Claude Code) see the world from their own point of view, act on
it, and keep doing ordinary coding work in your projects.

## Requirements

- Minecraft 1.21.1 with NeoForge 21.1.251+, singleplayer.
- At least one signed-in agent CLI: [Codex](https://github.com/openai/codex), or the
  native [Claude Code](https://docs.claude.com/en/docs/claude-code) CLI
  (`claude auth login`). Claude agents also need Node 20+ at runtime.
- To build: Java 21 and Node 20+.

Developed on macOS; Linux should work. Windows is untested, and `tools/dev` does
not support it. Pasting images/files into chat is macOS-only.

## Build and install

```sh
(cd tools/claude-bridge && npm ci --ignore-scripts)
tools/build build
```

Copy `build/libs/too-many-agents-<version>.jar` into your NeoForge instance's
`mods/` folder. `tools/build` uses `JAVA_HOME` if it is a JDK 21, otherwise it
looks for one in common locations.

## Using it

**Mod settings → Agent providers** shows each harness's selected version,
executable and source. Automatic mode picks the newest version found on PATH,
in common install directories, and in supported desktop installs; it never
installs or updates anything. Use Custom path to pin an executable. Custom
settings override `TOO_MANY_AGENTS_CODEX` / `TOO_MANY_AGENTS_CLAUDE`, which
override discovery; `TOO_MANY_AGENTS_NODE` selects Node. Changes apply after
restarting Minecraft.

**Projects** have a primary folder and optional additional folders. Agents work
in the primary folder, or in their own Git worktree when created with
**Worktree** checked; child agents share their parent's checkout unless they ask
for a new one. Worktrees are named from the agent's title (or first task) plus a
short id, e.g. `login-redirect-fix-4e7c`, and start from the current commit
without uncommitted files. Archiving or removing an agent keeps its files and
branch.

**In the world**, each project can have a bounding box and stations: labeled
boxes that each hold one agent. A body never leaves its station (or project box),
though it can reach blocks just outside. Press B for survey mode to see and draw
boxes. Idle behaviors (stand, wander, look, swing) are cosmetic and never change
the world.

**Data.** Managed folders live in `~/.too-many-agents/` (`worlds/<world-id>/`,
`worktrees/`, and `archive/` for archived chats). Override with
`TOO_MANY_AGENTS_DATA_DIR` or `-Dtoo_many_agents.dataDir`. Runtime state stays in
the game directory. Mod settings → Archive lists and restores archived agents.

**Local API.** The mod serves a loopback-only HTTP API for its tools, protected by
a random token in `<game dir>/too-many-agents/connection.json`.

## Development

See [AGENTS.md](AGENTS.md) for the development rules and test workflow, and
[TODO.md](TODO.md) for outstanding work.

```sh
tools/build runClient -PdevWorld   # isolated flat test world in run/
tools/dev                          # personal dev game in run/play/ with hot reload
tools/dev --test                   # hot reload in the test world
```

`tools/Play.command` launches `tools/dev` by double-click on macOS. `tools/dev`
needs a JetBrains Runtime 21 (enhanced HotSwap); it finds the one bundled with
Android Studio or IntelliJ, otherwise set `TMA_DEV_JAVA_HOME`.

Saving a Java edit compiles and reloads it into the running game. Method and
field changes generally work; constructors and static initializers do not rerun,
new fields need explicit initialization, and screens built in `init()` need
reopening. Strategy packages, resources, mixins, build files, deleted sources,
`AgentService`, and the `agent` package require a restart; the terminal says
when. A failed reload never resets agents or replays world actions. Each game
folder has `hot-reload-status.json` and logs. Validate releases with a fresh
packaged-JAR run (see AGENTS.md).

Diagnostics: `tools/agents.py --game-dir run …` (`list`, `projects`, `stations`,
`transcript AGENT_ID`, `ui …`) and `tools/too_many_agents.py --game-dir run state`.

`node tools/claude-bridge/bridge-smoke.mjs <empty absolute dir>` checks the
built Claude helper against the installed Claude Code with real model turns.

## License

[MIT](LICENSE). Licenses of npm packages bundled into the Claude helper ship in the JAR at `too_many_agents/claude/THIRD-PARTY-LICENSES.txt`.
