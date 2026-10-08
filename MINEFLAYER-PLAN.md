# Mineflayer scripting implementation plan

## Vision

Give Minecraft agents one unified, Mineflayer-compatible JavaScript API, including Pathfinder navigation, for controlling their existing native bodies. Agents should be able to execute useful sequences, loops, and conditional procedures in one script, with reliable outcomes and substantially fewer model round trips.

**The intended result and agreed requirements stay fixed; the route can evolve.** This is a small living plan: update implementation choices when evidence warrants it, and notify the user mid-run when changing the approach, explaining why and what it affects. Do not quietly reduce compatibility, substitute a different result, or redefine completion. For an important decision that needs the user's direction, pause the goal and explain the decision needed; routine implementation choices can proceed. Keep updates brief and useful, with code and executable checks remaining the source of truth.

## Agreed direction

- Implement the applicable public Mineflayer and Mineflayer Pathfinder APIs as one ready-to-use scripting surface. Agents receive a `bot` object with navigation already available through `bot.pathfinder`; they do not install or load the extension themselves.
- Do not fork either project. Pin upstream versions as the compatibility reference, and borrow implementation code selectively where it simplifies the work, retaining required attribution and licenses.
- Preserve existing native NPC bodies and singleplayer operation with an integrated server. The feature must not require extra Minecraft accounts, opening the user's world to LAN, or replacing bodies with network-connected players.
- Target the full applicable gameplay API over the implementation push. Early slices are checkpoints, not a permanent reduction to a small subset.
- Server connection and account authentication APIs are inapplicable to an already-bound native body. Compatibility with arbitrary third-party plugins or Mineflayer's private internals is a separate promise and is not required.
- Preserve upstream method signatures, object shapes, synchronous/asynchronous behavior, completion semantics, and documented failures wherever supported. Familiar method names alone do not establish compatibility.
- Compatibility means agents can use the familiar API correctly, not exact reproduction of every upstream behavior. The user clarified that minor differences in coordinates, routes, timing and other incidental details are acceptable. Verify meaningful task outcomes with appropriate tolerances; do not spend effort matching irrelevant numerical precision or upstream quirks. Wrong targets, lost items, false success, broken ownership and continued actions after cancellation remain correctness failures.
- Keep convenience extensions distinguishable from upstream methods. For example, do not silently make `bot.dig()` navigate if upstream requires the caller to approach first.
- Scripts retain the body's permissions and physical limitations. Survival inventory, tools, reach, and timing still matter; scripting does not grant commands, teleportation, or unrestricted world editing.
- The user confirmed keeping native Mob behavior: do not add player hunger/saturation, player respawn or a substitute player body. Health, death and immunity remain native observations/behavior; player-only mechanics are not prerequisites for this port.

## Architecture

```text
Agent submits JavaScript through a registered Minecraft script tool
  → BB plugin binds execution to that thread, body, and world session
  → isolated script runner exposes the unified bot API
  → API operations pass through the plugin's existing local HTTP bridge
  → Java schedules and executes native work on Minecraft's owning threads
  → completion, state updates, or failures return to the script
  → the agent receives the script's result or actionable failure
```

The BB plugin owns script execution, API objects, request forwarding, and translating results into promises and errors. Java owns authoritative game state, native actions, navigation, and execution safeguards. Keep this within the existing plugin and single Gradle module unless a change clearly simplifies the implementation.

The runner uses QuickJS WASM in a Node worker, with bounded memory, execution, and JSON messages. Scripts receive no host module loader or connection credentials. Java retains a renewable, expiring body-control lease. Native completion waits replace action polling; they complete from the owning thread without blocking it, and close with the execution's world/turn scope.

Each awaited action resolves on actual completion or reports failure. Longer script runs expose progress and can be cancelled without forcing the model to poll every individual action. Existing status polling may be hidden inside the client during the first slice; the intended interface should support efficient completion waits.

Mineflayer also exposes immediately readable properties and synchronous queries. The runner therefore needs a local view of relevant blocks, entities, inventory, and other state, supplied by Java. Define initialization, updates, freshness, and unloaded-state behavior explicitly. Java validates mutations against the current world rather than trusting a cached observation. Do not make upstream synchronous methods asynchronous merely because the transport is HTTP.

The implemented observation path combines a per-frame 33×17×33 local view with complete, already-loaded columns in a 5×5 area around the body. Wider columns refresh within five ticks and on completed actions or chunk changes; updates patch stable guest columns before events and promise completion. Missing columns stay unknown, and waiting does not force terrain loading. Selected native wire, light, biome and freshness checks passed in the flat test world; worst-case frame cost and wider coverage remain unverified. Remaining World APIs stay in scope.

Continuous state uses one authenticated HTTP stream per script, with a bounded queue and ordered frames produced on the owning game thread. After initialization this stream is the sole source of state changes. Action promises wait until the stream includes their native completion sequence, so their return cannot overtake inventory, entity, or block events. Tick waits count the same physics events, with bounded guest timers for timeouts. Block deltas reduce repeated data; snapshot revisions detect ordering failures. Delivery into QuickJS is acknowledged and subject to the same execution limits as the script. Stream loss stops execution; it does not trigger a reconnect or replay.

The runner should retain control of the body between script actions so ambient following or wandering cannot displace it mid-procedure. Restore configured ambient behavior when execution releases the body. Scripts use the authorized bridge; connection credentials remain with the plugin.

Preserve these lifecycle rules throughout implementation:

- Bind every execution and queued action to the correct BB thread, body, and world session; expire work that never starts.
- Bound execution and output, including scripts that loop without awaiting. Do not execute arbitrary agent JavaScript on Minecraft's server thread.
- Propagate cancellation and world departure to outstanding work and prevent subsequent script steps from starting.
- Distinguish cancellation, failure, completed mutations, and unknown outcomes. Report partial completion; never automatically replay uncertain mutations.
- Never block Minecraft's owning thread waiting for JavaScript, HTTP, or a model response.

## Implementation sequence

### 1. Establish the reference and compatibility target

Pin Mineflayer and Pathfinder versions compatible with Minecraft 1.21.1. Use upstream documentation, declarations, source, and examples together as the reference.

Create a concise compatibility checklist covering public methods, properties, events, and relevant objects. Record supported, pending, and intentionally inapplicable features, with links to executable evidence. Pending gameplay features remain required work; exclusions beyond the agreed boundary need user review.

Prepare a separate local reference server and disposable test world, with pinned Node dependencies and repeatable fixture setup. Use loopback-only access and offline authentication so reference bots need no Microsoft login. This server is a test reference, not multiplayer support for the mod. Surface any required Minecraft server EULA acceptance to the user.

Node 24 and a Java 21 runtime were found during planning. Recheck availability in the implementation environment rather than relying on machine-specific paths. Keep downloaded upstream checkouts, servers, worlds, caches, and generated output ignored.

### 2. Prove one complete native-body script

Build one end-to-end slice through the registered script tool: find a known block, navigate into reach, equip an available tool, mine it, collect the drop, and return inventory information. Follow the pinned API's semantics rather than inventing shortcuts under upstream names.

Verify actual world and inventory results independently. Include cancellation during movement/mining, world departure, and a meaningful failure. Establish state synchronization and script control of the body before expanding the interface broadly.

### 3. Resolve Pathfinder reuse early

Investigate whether the existing Pathfinder implementation can run against an adapter supplying the expected world data and body controls. If satisfying its assumptions would recreate most of a network client, adapt useful planning code or implement the required behavior using native controls instead.

This is a feasibility question, not a predetermined rewrite. Explain the findings and any material change of approach to the user. Supporting `goto()` through today's Java navigation is a useful first slice, but does not by itself establish Pathfinder conformance. Preserve goal behavior, movement settings, cancellation, and the applicable digging/placing capabilities as coverage expands.

The source-only feasibility review supports reusing upstream AStar, heap, Move, goals, and movement-policy logic in QuickJS. Adapt movement generation to actual body dimensions and use bounded native execution of the selected route edges; unrestricted native waypoint navigation cannot enforce Pathfinder's route policies. Continuous ordered state updates and stable entity objects are prerequisites for dynamic goals and events. Preserve documented behavior over reproduced upstream defects, recording explicit differential exceptions: pinned `goto` can resolve an empty `noPath` result, and `GoalBreakBlock.isEnd` omits the node argument. Neither defect justifies dropping an API.

Live water checks also exposed upstream planning gaps: its landing scan skips an immediately adjacent water cell, and it omits vertical swimming edges. Correct these explicitly, retaining native swimming capabilities, collision clearance, movement policies, and ordinary physics. Native completion must satisfy the selected logical cell; completing a full route elsewhere must fail instead of repeating the route indefinitely.

### 4. Expand in coherent, verified groups

Work through observations and API objects; basic physical actions; navigation; containers and crafting; then remaining applicable gameplay features and events. Reorder groups when dependencies or evidence justify it, keeping the compatibility target unchanged.

Keep implementation moving while a dedicated BB child runs important integration checks on the last installed build. Use Codex 6.1 Sol low for this tester, medium for difficult failures. The lead coordinates builds/restarts and identifies the exact revision under test; the tester reports failures and partial outcomes without replaying unknown actions.

The user requested a code-first finishing pass with reduced test repetition on October 7. Integrate the remaining bounded ports first, then prioritize one combined native check and actual packaged validation; do not rerun already passing suites without a concrete failure or changed dependency. This changes verification order, not the compatibility target.

Use real scripts to establish each group's behavior. Reuse upstream implementations where practical; avoid a growing collection of aliases with incompatible behavior. Supply concise API guidance and examples to agents, clearly identifying extensions and any pending coverage.

### 5. Complete integration and packaged validation

Run the broader conformance scenarios, Codex agent tasks, lifecycle checks, and the actual packaged mod. Completion requires evidence for the agreed API coverage, not merely a compiling adapter or a successful demonstration script. Any unresolved requirement remains visible and is not silently reclassified as out of scope.

## Conformance and performance verification

Define meaningful scenarios before implementing each group. Prefer deterministic end-to-end checks and independent world observations; do not add after-the-fact unit tests that merely mirror the implementation.

| Evidence | What it establishes |
| --- | --- |
| Contract checks against pinned declarations and documentation | Names, arguments, object shapes, events, synchronous versus asynchronous behavior, and error/completion contracts. Type checking alone is insufficient. |
| Matching scripts against real Mineflayer and our implementation | Equivalent observable behavior under controlled starting conditions. |
| Real Codex tasks in the mod | Agents can discover the API, compose scripts, interpret results, and recover from failures. |

Start with equipping, mining, placement, navigation around an obstacle, container transfers, and crafting. Expand scenarios with API coverage. Compare returned values and errors as well as independently observed positions, blocks, and inventory. Avoid comparing only our output to our own expectations or requiring identical walking paths and elapsed ticks.

Exercise missing materials, unsuitable tools, blocked placement, unreachable or disappearing targets, unloaded areas, cancellation, pause/resume, world switching, and lost replies. Document legitimate body-specific differences such as collision size. If existing body behavior conflicts with the agreed gameplay compatibility, raise the conflict rather than silently excluding the API.

Reference Mineflayer bots are deterministic programs, not AI agents. Keep their fixtures separate from the mod's guarded test worlds. Run the same script where possible; isolate setup differences and report necessary script changes honestly.

Measure task correctness, elapsed time, model-visible tool calls, local bridge operations, and game ticks, recording the model and reasoning setting for AI runs. Fewer model calls must not hide unsuccessful work. Use a small wall-building task without commands and a survival gathering/crafting task to compare with the current direct-tool workflow; do not create a separate evaluation platform.

## Branch, agents, and test logistics

Use a new `feat/mineflayer-api` branch in an isolated worktree. Preserve unrelated work in the current checkout. Creating this document does not create the branch, start a goal, or launch agents.

| Work | Provider/model preference |
| --- | --- |
| In-world AI smoke checks and simple scripts | Codex, Luna, low reasoning. |
| In-world multi-step tasks and recovery | Codex, 6.1 Sol, low reasoning. |
| BB implementation children | Astra, low through high according to complexity; medium for ordinary API work, high for difficult navigation/lifecycle work. |
| Focused native verification coordinator | Codex, 6.1 Sol, low or medium; tests run alongside implementation. |

All in-world AI test agents must use Codex. Resolve exact model identifiers against the installed catalog when execution begins; do not silently substitute another provider or reinterpret these preferences as settings for implementation children.

Start with one lead implementation agent for the tightly coupled first slice. Introduce BB child agents only when responsibilities and the shared contract are clear. A likely split is JavaScript API/runner work and Java actions/navigation work, with a focused verification child when useful. These are BB children, not provider-native subagents. Use explicit file ownership and separate worktrees; the lead owns integration, contract changes, and compatibility coverage.

Serialize live tests against the shared mod test client. Only one agent at a time may mutate its fixtures, restart it, or reload the plugin. Never replace the plugin while another game is connected. Coordinate any transition between source and packaged runs.

Follow `AGENTS.md` for builds and live diagnostics:

- Use `tools/build` with Java 21 and the guarded `run/` development world; never use personal `run/play/` worlds for fixtures.
- Use `tools/too_many_agents.py`, `tools/agents.py`, `tools/smoke.py`, and the native UI/interaction hooks for checks.
- Rebuild/restart for Java or physical-schema changes; follow the normal plugin setup/reload flow for plugin changes.
- Build with `tools/build build`, then separately run `tools/build runPackagedClient -PpackagedJarRun -PdevWorld`, stopping the previous identified test client first.
- Verify matching built/staged JAR hashes, exactly one loaded mod, and a packaged classpath excluding project classes/resources. Exercise POV and native interactions, then reopen the test world to check persistence.
- Save/disconnect before stopping the exact identified test JVM. Do not manage the user's BB installation or interrupt their game.

Report what actually ran, measured results, and remaining limitations. Keep this document focused on direction and decisions rather than duplicating implementation summaries.

## Upstream references

- [Mineflayer 4.39.0 API](https://github.com/PrismarineJS/mineflayer/blob/c168635cf2f6602069fc5e408bec864702335581/docs/api.md)
- [Mineflayer 4.39.0 examples](https://github.com/PrismarineJS/mineflayer/tree/c168635cf2f6602069fc5e408bec864702335581/examples)
- [Mineflayer Pathfinder 2.4.5](https://github.com/PrismarineJS/mineflayer-pathfinder/tree/ca35a00ec18e7d3095280ffe2dc194e7c81b55eb)
- [Voyager](https://github.com/MineDojo/Voyager), a reference for AI-written Minecraft procedures rather than a framework to adopt.

Exact package versions and upstream revisions are in `tools/mineflayer-reference/upstream.json`; its dependency lockfile pins the reference installation. `coverage.json` records decisions and evidence; the generated catalog inventories declarations and documentation headings without treating their presence as a conformance pass.
