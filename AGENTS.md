## Development

- Use single hyphens for Minecraft UI text separators and unordered list markers, never middle dots or bullet glyphs.
- Start with the user's experience and one working end-to-end slice. Do not infer an enterprise architecture from Java conventions.
- One Gradle module, one mod JAR, ordinary Java. A new interface, service layer, library, or module needs a concrete simplification to justify it.
- Use `tools/build` (Java 21), `tools/game`, and `tools/check`. Inspect Minecraft's locally generated sources when an API is uncertain. Do not infer runtime success from compilation.
- Code is the source of truth. Do not recreate feature inventories, implementation summaries, or milestone reports unless requested. Keep unresolved user requests in TODO.md.
- Agent instructions and coordination tools live in `bb-plugin/`. Physical tool schemas in `surface/tools.json` are shared by the plugin and Java dispatch. BB owns conversations, provider/model selection, approvals, queues, projects, and worktrees; Java owns game objects, world records, and execution safeguards. Use `tools/dev reload CLIENT` or Reload changes in the Escape menu after edits. The supervisor builds before applying changes, reloads only the affected plugin, and saves/restarts Minecraft for Java, resources or physical-schema changes.
- BB is an installed, user-managed dependency. The managed launcher installs one namespaced plugin per checkout/client; managed Minecraft only verifies and connects to that plugin. Ordinary packaged setup retains the default `minecraft` plugin; the plugin advertises BB's loopback address through short-lived records in the mod-owned `~/.too-many-agents/bb/` directory. Games need no BB paths or environment variables. Do not download, start, stop, upgrade, or replace BB as part of the mod workflow or its tests; that instance may serve unrelated user work.
- Prefer live integration checks. Report what actually ran and any remaining limitations in the task result; do not add tautological unit tests after implementation.
- `bb-plugin/package.json` owns the shared mod/plugin release version. `tools/build build` bundles the built plugin and its runtime dependencies into the JAR. Test setup through `ui bb_setup`; at the development title screen, setup and managed lifecycle UI inputs work without a world. Reloading the plugin while the user's game is connected is fine; it only briefly pauses that game's BB connection. Run a test client alongside the user's game when needed.
- Game objects stay on their owning thread. HTTP handles serialized snapshots and schedules bounded actions; it never reads the live world directly.
- Every queued world action identifies its world session and expires if not started. Physical callbacks must come from the agent's own BB thread. Never automatically retry an action whose outcome is unknown.
- Keep the local connection descriptor/token out of source control and output. Bind to loopback.
- Install only our own JAR/scripts. Do not replace the user's worlds, launcher configuration, or unrelated mods. Java and resource changes require a rebuild and restart.
- Keep game runs, caches, and generated output ignored. Do not commit upstream game sources or JARs.

Current scope is a singleplayer physical client with an integrated server. Multiplayer support is not established. Keep that boundary explicit rather than presenting local shared-memory access as a multiplayer transport.

## Testing Minecraft yourself

This project has its own test window and in-mod diagnostics; ordinary desktop automation is not required for most checks.

- `tools/dev start alpha` creates/reopens a guarded test world in `run/clients/alpha/`; `tools/dev --test` uses the existing guarded world in `run/`. Each named client has its own plugin, browser profile and logs. Multiple worktrees and clients may use the same BB. Use a dedicated client for each concurrent test driver.
- `tools/dev` without arguments uses the personal game in `run/play/`; never use its worlds for fixtures. Use `tools/dev reload CLIENT` after edits and `tools/dev stop CLIENT` to save and stop. Never infer runtime success from compilation.
- Managed clients run frozen packaged JARs. Confirm artifact and staged JAR hashes match, the runtime lists the mod once, and the classpath excludes project classes/resources. Exercise POV and native interactions, then reload to check persistence. To test ordinary setup separately, run `tools/build build`, then `tools/build runPackagedClient -PpackagedJarRun -PdevWorld`; stop the managed `test` client first. Keep these invocations separate because compilation needs the development access transformer.
- Target diagnostics explicitly: `tools/game --client alpha state` and `tools/game --client alpha ...`. Use `tools/check --client alpha CHECK` for existing acceptance checks (`tools/check --help` lists them). Tokens are read privately from the game's connection descriptor; never print them. Checks require the guarded world and write result records beside it.
- `tools/game --client alpha ui widgets` reads native widget bounds; `ui screenshot` captures the real framebuffer. `ui click`, `ui hover`, `ui text`, `ui key`, and `ui scroll` with `--json` exercise the actual screen/event handlers. `ui list` opens the player's inventory with agent panels; `ui vanilla_chat`, `ui inventory --agent ID`, and `ui mod_settings` cover chat mentions and the other views. See `ClientControls.java` for arguments. These hooks, agent tools, and independent world observations let us test UI, POV, physical actions, and real Codex turns without asking the user to click through them.
- `tools/game --client alpha dev native-checks` starts the native interaction fixture; `dev status` reports results. `ui dev_pause`, `ui dev_resume`, and `ui dev_leave` exercise vanilla pause/resume/save-and-disconnect. These development controls require the dev flag and exact test-world folder. `ui dev_gui_scale --json '{"scale":3}'` checks layout at a different native GUI scale; `ui dev_key --json '{"key":71}'` drives Minecraft’s own keyboard handler for keybind checks. Both have the same development-world guard. For world-isolation checks, `ui dev_switch_world` saves/disconnects and toggles only between that world and the separate flat `too-many-agents-development-other`; it requires the development flag and rejects all other source worlds. `ui dev_open_world --json '{"name":"too-many-agents-development"}'` opens only either guarded world from the development title screen, with no server running.
- Save/disconnect before stopping only the identified dev JVM. Never stop the user's game or use their world for fixtures. Report actual results and remaining manual checks in the task result.

### Tips

Things that have tripped up past sessions; they depend on the harness, so treat them as hints rather than rules.

- If the client log stops at `Backend library: LWJGL` and `connection.json` never appears, the shell probably can't open a window (seen under Claude Code's macOS sandbox). Launching outside the sandbox fixed it. Access-transformer changes regenerate Minecraft artifacts in the system temp directory, which can fail the same way.
- `ui hover` places Minecraft's own cursor, so tooltips and hover states show up in `ui screenshot` without moving the desktop pointer.
- `ui widgets`, `ui click`, `ui hover`, and `ui scroll` use the open screen's own coordinates, which differ from the screenshot's `guiWidth` when the agent screen GUI scale is set.
