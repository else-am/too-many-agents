## Development

- Start with the user's experience and one working end-to-end slice. Do not infer an enterprise architecture from Java conventions.
- One Gradle module, one mod JAR, ordinary Java. A new interface, service layer, library, or module needs a concrete simplification to justify it.
- Use `tools/build` (Java 21), `tools/too_many_agents.py`, and `tools/smoke.py`. Inspect Minecraft's locally generated sources when an API is uncertain. Do not infer runtime success from compilation.
- Code is the source of truth. Do not recreate feature inventories, implementation summaries, or milestone reports unless requested. Keep unresolved user requests in TODO.md.
- Agent instructions and coordination tools live in `bb-plugin/`. Physical tool schemas in `surface/tools.json` are shared by the plugin and Java dispatch. BB owns conversations, provider/model selection, approvals, queues, projects, and worktrees; Java owns game objects, world records, and execution safeguards. Restart development after plugin changes (or build with `tools/build packageBbPlugin`, reload through mod settings; the current world stays open while its BB connection pauses) and rebuild/restart Minecraft for Java or physical-schema changes.
- BB is an installed, user-managed dependency. Source launch commands build our plugin and let Minecraft install/reload the checkout through its normal BB setup; the plugin advertises BB's loopback address through short-lived records in the mod-owned `~/.too-many-agents/bb/` directory. Games need no BB paths or environment variables. Do not download, start, stop, upgrade, or replace BB as part of the mod workflow or its tests; that instance may serve unrelated user work.
- Prefer live integration checks. Report what actually ran and any remaining limitations in the task result; do not add tautological unit tests after implementation.
- `bb-plugin/package.json` owns the shared mod/plugin release version. `tools/build build` bundles the built plugin and its runtime dependencies into the JAR. Test setup through `ui bb_setup`; at the development title screen, only setup UI inputs are allowed without a world. Never replace a plugin while another game is connected.
- Game objects stay on their owning thread. HTTP handles serialized snapshots and schedules bounded actions; it never reads the live world directly.
- Every queued world action identifies its world session and expires if not started. Physical callbacks must come from the agent's own BB thread. Never automatically retry an action whose outcome is unknown.
- Keep the local connection descriptor/token out of source control and output. Bind to loopback.
- Install only our own JAR/scripts. Do not replace the user's worlds, launcher configuration, or unrelated mods. Java and resource changes require a rebuild and restart.
- Keep game runs, caches, and generated output ignored. Do not commit upstream game sources or JARs.

Current scope is a singleplayer physical client with an integrated server. Multiplayer support is not established. Keep that boundary explicit rather than presenting local shared-memory access as a multiplayer transport.

## Testing Minecraft yourself

This project has its own test window and in-mod diagnostics; ordinary desktop automation is not required for most checks.

- Launch `tools/build runClient -PdevWorld` from this directory. It creates/reopens the isolated flat world `run/saves/too-many-agents-development` and disables pause on lost focus. The ignored `run/` directory retains test worlds and agents across restarts, separate from the user's own Minecraft installation.
- `tools/dev --test` builds and launches that same guarded test world. `tools/dev` without `--test` uses the user's personal development game in `run/play/`; do not use its worlds for fixtures. Both use ordinary Java 21, with no hot reload. Close Minecraft and rerun after code changes. Use the connection descriptor's exact PID when managing a test client, and never infer runtime success just from compilation.
- Before installing, test the actual JAR: run `tools/build build`, then separately `tools/build runPackagedClient -PpackagedJarRun -PdevWorld`. Stop the previous dev client first. Keep the invocations separate: compilation needs the development access transformer; the packaged run disables that input and loads the built JAR. Confirm the built and staged JAR hashes match, the runtime lists the mod once, and the packaged classpath excludes project classes/resources. Exercise POV and native interactions, then reopen the test world to check persistence.
- Point diagnostics explicitly at `run`: `python3 tools/too_many_agents.py --game-dir run state` and `python3 tools/agents.py --game-dir run ...`. The tools read `run/too-many-agents/connection.json` privately; never print its token.
- `tools/agents.py ... ui widgets` reads native widget bounds; `ui screenshot` captures the real framebuffer. `ui click`, `ui hover`, `ui text`, `ui key`, and `ui scroll` with `--json` exercise the actual screen/event handlers. `ui list` opens the player's inventory with agent panels; `ui vanilla_chat`, `ui inventory --agent ID`, and `ui mod_settings` cover chat mentions and the other views. See `ClientControls.java` for arguments. These hooks, agent tools, and independent world observations let us test UI, POV, physical actions, and real Codex turns without asking the user to click through them.
- `tools/agents.py ... dev native-checks` starts the native interaction fixture; `dev status` reports results. `ui dev_pause`, `ui dev_resume`, and `ui dev_leave` exercise vanilla pause/resume/save-and-disconnect. These development controls require the dev flag and exact test-world folder. `ui dev_gui_scale --json '{"scale":3}'` checks layout at a different native GUI scale; `ui dev_key --json '{"key":71}'` drives Minecraft’s own keyboard handler for keybind checks. Both have the same development-world guard. For world-isolation checks, `ui dev_switch_world` saves/disconnects and toggles only between that world and the separate flat `too-many-agents-development-other`; it requires the development flag and rejects all other source worlds. `ui dev_open_world --json '{"name":"too-many-agents-development"}'` opens only either guarded world from the development title screen, with no server running.
- Save/disconnect before stopping only the identified dev JVM. Never interrupt the user's game or use their world for fixtures. Report actual results and remaining manual checks in the task result.

### Tips

Things that have tripped up past sessions; they depend on the harness, so treat them as hints rather than rules.

- If the client log stops at `Backend library: LWJGL` and `connection.json` never appears, the shell probably can't open a window (seen under Claude Code's macOS sandbox). Launching outside the sandbox fixed it. Access-transformer changes regenerate Minecraft artifacts in the system temp directory, which can fail the same way.
- `ui hover` places Minecraft's own cursor, so tooltips and hover states show up in `ui screenshot` without moving the desktop pointer.
- `ui widgets`, `ui click`, `ui hover`, and `ui scroll` use the open screen's own coordinates, which differ from the screenshot's `guiWidth` when the agent screen GUI scale is set.
