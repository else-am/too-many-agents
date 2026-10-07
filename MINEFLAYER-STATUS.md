# Mineflayer API port — handoff checklist

Updated October 7, 2026. **The full port is not complete. There is still implementation work, not just testing.** This is a practical checklist, not a percentage or a claim of exhaustive compatibility. The target remains [MINEFLAYER-PLAN.md](MINEFLAYER-PLAN.md).

## Exact checkpoint

- Branch: `feat/mineflayer-api`.
- Checkout: `/Users/scott/Else/too-many-agents/scratch/worktrees/mineflayer-api`.
- Last built and live-validated implementation: **`880c87b`**.
- Built artifact: `build/libs/too-many-agents-0.9.0.jar`.
- Validated JAR SHA-256: `b0448ff51ff20ac5e360ab1aa7e17c8b87cdfa65ed1a7b779ef33587a7f56614`.
- **Fishing implementation `fc483c4` passes the full Java/plugin/package build; its first native run verified replacement cleanup, but stopped on an incorrect test assertion before a catch.** The build output may be newer than the last validated JAR hash above; do not confuse the two. See the fishing handoff below.
- Latest test JVM `39428` was stopped after a server iterator stall. **Saving this run was not confirmed**; re-observe fixtures after restart. Root owns lifecycle while rebuilding. Earlier `880c87b` persistence evidence remains valid.
- Minecraft 1.21.1; Mineflayer 4.39.0; Pathfinder 2.4.5. Exact dependencies and source revisions: [upstream.json](tools/mineflayer-reference/upstream.json).

## Implemented

Checked boxes mean the described implementation exists. Verification is listed separately; **a checked group does not mean every member, event or edge case is fully conformant**.

### Script execution and state

- [x] One `minecraft_run` call executes async JavaScript, loops and sequences against an existing native body.
- [x] Isolated QuickJS worker, bounded memory/CPU/deadline/output; no guest filesystem, network, credentials or Node module loader.
- [x] Thread/body/world ownership, expiring control lease, cancellation and cleanup; unknown outcomes abort without automatic replay.
- [x] Ordered state stream; action promises wait for their authoritative state. Tick waits and synchronous control draining.
- [x] Stable Entity/Vec3 references, inventory/windows, held/equipped items and observed vehicle/passenger relationships.
- [x] Typed Block, Item, Window, Entity, ChatMessage/MessageBuilder, Recipe/RecipeItem and Vec3 objects; component/NBT transport.
- [x] Player lists and UUID/name mapping; stable game state; time, health, oxygen, weather, XP and version feature queries.

### Observation and ordinary gameplay

- [x] Block/entity search, cursor queries, visibility checks and world raycasting against the observed cache.
- [x] Look/lookAt, digging and stopping, dig estimates, block placement, block/entity activation.
- [x] Equip/unequip, quickbar selection, clicks, transfers, tossing and ordinary container operations.
- [x] Native crafting with actual outputs and remainders, including cake buckets; no synthetic recipe outputs.
- [x] Furnace-like, enchantment, anvil and villager window adapters; native properties, XP and trade outcomes.
- [x] Book writing/signing with native component preservation, identity checks and guarded slot restoration.
- [x] Attack/swing, item activation/release, eating and drinking on the actual body, native shield-use plumbing.
- [x] Entity-placement adapter; armor stands and boats have live checks. Other accepted item types need coverage.
- [x] Manual forward/back/strafe/jump/sprint/sneak controls, clear controls and mount/dismount.
- [x] Sign text updates after acquiring the native editor through interaction.

### Pathfinder

- [x] Reused upstream AStar/heap/Move/goals, with body-aware Movements and synchronous/resumable planning.
- [x] `goto`, `setGoal`, dynamic-goal plumbing, `setMovements`, stop, planning methods, best tool and movement/mining/building queries.
- [x] Native execution of selected route edges with collision/revision checks, explicit digs/placements and real inventory costs.
- [x] Ground routes, slabs/steps, jumps/gaps, drops, ladders, swimming, bridge placement and tower placement implemented.
- [x] Known upstream defects corrected explicitly rather than copying false success or lost actions.

## Verified so far

These are focused results, not proof of the whole API.

- [x] Pinned-library comparisons in actual QuickJS for Blocks, Items/wire transport, Windows, recipes, Chat/Entity objects, world queries, movement planning, inventory/crafting, books and specialized windows.
- [x] Native mining/gathering, ordered state/events, stable references, inventory components and block-entity observations.
- [x] Native navigation fixtures: ground/shapes, gap jump, mining, bridging, tower, ladder, water entry/exit and vertical swimming; selected cancellation/lifecycle cases.
- [x] Inventory/container/equipment/full-inventory cases; native crafting and partial failure with preserved results.
- [x] Packaged combined furnace/book/anvil/enchantment/trade/look/dig/place check: **64/64 requests completed**; independent world/inventory evidence.
- [x] Reopened the world and verified persistence of those earlier results.
- [x] Packaged combat, armor-stand/boat placement, manual movement/stopping, jump, apple consumption and same-script shield activation/release.
- [x] Final `880c87b` checks: actual body mounted/dismounted; potion and milk consumed with bottle/bucket returned before promise resolution; effects applied/cleared on the body; unrelated inventory preserved; sign and player/game data correct.
- [x] Actual packaged JAR hashes matched, mod loaded once, project classes/resources excluded; native BB setup succeeded. POV was captured and inspected in the earlier packaged pass.
- [x] Final Java build, plugin typecheck/bundle and packaging passed for `880c87b`.
- [x] Fishing action and body-aware hook adapter compile with Java 21; JavaScript syntax passes.
- [x] Fishing replacement interruption and script cleanup: the native hooks were removed and the rod remained unchanged (`fc483c4`).
- [x] Fishing corrected replacement sequence: distinct hooks, at most one active, predecessor rejected with the cancellation code; second attempt retrieved naturally. Bobber/line framebuffer inspected.
- [ ] Fishing post-retrieval progress, loot/XP/durability and cleanup on the rebuilt iterator fix. The prior run stalled after retrieval; it is not a full pass.
- [ ] Exhaustive native conformance, all body species, all lifecycle combinations and paired live Mineflayer-server comparisons.

Key evidence (local BB thread storage):

- [Essential packaged workflows](</Users/scott/.bb/thread-storage/thr_xykqkgui57/packaged-essential-summary.json>)
- [Persistence, combat and entity placement](</Users/scott/.bb/thread-storage/thr_xykqkgui57/combat-focused-summary.json>)
- [Movement, jump, eating and shield use](</Users/scott/.bb/thread-storage/thr_xykqkgui57/eede028-focused-summary.json>)
- [Final fixes, signs and player/game state](</Users/scott/.bb/thread-storage/thr_xykqkgui57/880c87b-final-focused-summary.json>)
- [Fishing replacement/retrieval/rendering and iterator stall](</Users/scott/.bb/thread-storage/thr_xykqkgui57/fc483c4-corrected-fishing-summary.json>)

Earlier failed mount/potion checks are superseded by the final successful build. Post-script shield release was expected cleanup, not a production defect. Several test failures were fixture mistakes; do not treat every historical failure as an unresolved product bug.

## Still to implement or finish

- [x] **Fishing implementation:** scoped action, native bite/retrieval/loot, cancellation and a body-aware hook with native renderer.
- [ ] **Fishing verification:** actual catch, loot/XP/durability, rendering and scoped cancellation.
- [x] **Boat steering implementation:** `moveVehicle` and manual inputs use the native boat controller, enforce controlling-seat ownership and clear inputs on release. Java/plugin/package build passed.
- [ ] **Vehicle steering verification and other mounts:** boat movement/turning/release await the focused live check. Horses, pigs, striders and minecarts remain implementation work.
- [ ] **Creative gameplay API:** arbitrary item/component setters and remaining creative movement APIs. Existing physical `creative_item` is not the full Mineflayer creative API.
- [ ] **Bed/sleep/wake and remaining body-specific gameplay:** determine truthful native-body behavior without inventing player hunger or other unavailable state.
- [ ] **Chat gameplay API:** sending/receiving, patterns and related events. Ported ChatMessage formatting is not the chat transport/API.
- [ ] **World/chunk API:** remaining applicable world/chunk methods and load events. Current synchronous observations cover a bounded **33×17×33** region; missing/unloaded cells are not known air.
- [ ] **Events and observations:** remaining entity/effect/damage/animation/collection, sound/particle/explosion, spawn, scoreboard/team/boss-bar/title/tab-list and other public events/data. Audit applicability; do not silently exclude them.
- [ ] **Special menus/actions:** horse inventory, beacon-specific operations and other applicable menu hooks; broader native verification of already implemented specialized menus.
- [ ] **Inventory click modes 5/6:** drag/double-click remain explicitly unsupported, as in pinned upstream; make the compatibility decision explicit.
- [ ] **Pathfinder completion:** shortcut/free-motion behavior, wider dynamic/custom-goal coverage, species-specific physics and remaining route-policy/geometry combinations.
- [ ] **Permission/lifecycle coverage:** remaining cancellation, world/session change, stale handles, timeout and unknown-reply combinations across new APIs.
- [ ] **Complete member-by-member audit:** reconcile declarations, documentation, source and implementation, including dependency objects and ambiguous applicable APIs.
- [ ] **Update evidence/catalog records:** `coverage.json` and the generated catalog are conservative and lag newer native results. Their pending counts are not an implementation percentage.
- [ ] **Paired live reference runs:** reference server is prepared, but Minecraft server EULA acceptance remains unanswered. Do not accept/start it without authorization.
- [ ] **Performance comparison:** complete the planned wall-building and gathering/crafting comparison against direct tools, using meaningful outcomes/tool counts/time.
- [ ] **Final full-scope packaged/persistence validation** after remaining ports. Recent changes have focused verification, not a new exhaustive persistence cycle.

Connection/account setup and Mineflayer's internal packet client are the agreed inapplicable pieces. Multiplayer and arbitrary third-party plugin compatibility are not promised. Other unclear APIs remain pending review rather than automatically excluded. Use [the catalog guide](tools/mineflayer-reference/README.md) and pinned source to find omissions; this grouped checklist is not the complete declaration inventory.

## Fishing: resume here

Implementation files:

- `bb-plugin/scripting/actions.mjs`: async `bot.fish()` with scoped start/wait/cancel and replacement handling, following the existing consume lifecycle.
- `src/main/java/toomanyagents/AgentHands.java`: begin/tick/cancel fishing through the existing native interaction proxy; native rod use handles retrieval, loot, XP and durability.
- `src/main/java/toomanyagents/AgentActions.java`: bounded `fish` action and terminal cleanup.
- `src/main/resources/META-INF/accesstransformer.cfg`: access to native `FishingHook.biting`, avoiding guessed particle/timer completion.
- `src/main/java/toomanyagents/BodyFishingHook.java`: registered native-hook subclass, body self-collision exclusion, native projectile launch, and a render-only client owner adapter.
- `TooManyAgents.java` registers the hook/renderer; `Observations.java` exposes its familiar `fishing_bobber` name.
- [Prewritten scenarios](tools/mineflayer-reference/fishing-native-scenarios.md).

The player-owner constraint now has an implementation: the server keeps the existing body-owned interaction proxy, while a client-only, unregistered render adapter follows the actual body. No network player or account is created. The hook excludes the visible body/vehicle from its own collision and retains native fishing timing, loot, XP and rod damage. Its launch uses Minecraft's projectile helper rather than copying exact player casting coordinates.

**Current:** the corrected `fc483c4` test passed cancellation/replacement and natural retrieval, then the server stopped advancing during pickup. Cancellation errors use `error.code === "FishingAborted"`, with `name === "ActionError"`; upstream itself uses a plain Error. The first test incorrectly asserted the name and stopped before a catch. [First-run evidence](</Users/scott/.bb/thread-storage/thr_xykqkgui57/fc483c4-fishing-summary.json>).

**Known blocker, fix committed as `6e293c7` (build passed; live verification pending):** the server thread spins in `GameAccess.tick` while iterating the live entity collection. Fishing changes that collection by removing hooks and adding loot. The fix collects bodies before executing their actions, so world mutations cannot invalidate the active iterator. [Thread dump](</Users/scott/.bb/thread-storage/thr_xykqkgui57/fc483c4-corrected-thread-dump.txt>).

**Next:** use the newly built package to verify post-retrieval progress and actual loot/XP/durability once. Confirm the bobber and line render, native loot/XP and durability change, and cancellation removes only this body's hook. Compilation alone does not establish those outcomes. Access-transformed artifacts have been regenerated successfully.

## Additional work in progress

Boat steering source was added after the fishing build: `actions.mjs`, `AgentActions.java`, a `Boat.controlBoat()` access transformer, and [prewritten scenarios](tools/mineflayer-reference/vehicle-native-scenarios.md). It uses native steering/physics, requires the actual body to control the boat, clears inputs on release, and enforces loaded/world/body-box boundaries. **Java/plugin/package build passed; live steering remains unverified.** The next focused test uses both this adapter and the fishing iterator fix. Other mount types remain pending.

## Working approach and logistics

- Keep porting first; run only important new/changed E2E cases. Do not repeat all passing suites or chase irrelevant coordinate precision. Wrong targets, item loss, false success and ownership failures still matter.
- Implementation children: BB threads, Astra as appropriate. Dedicated test coordinator: `thr_xykqkgui57` (Codex 6.1 Sol medium); in-world executor: `thr_tp9qyhq6ed` (Codex 6.1 Sol low). Check their current BB status before resuming. Never give overlapping lifecycle ownership.
- Test body UUID: `3793b7e5-d567-45f5-8767-8ca5151863a0`, bound to the in-world executor. Re-observe fixtures; do not assume historical positions/inventory.
- Use `tools/build build`, then separately `tools/build runPackagedClient -PpackagedJarRun -PdevWorld`. Follow AGENTS.md for exact-PID shutdown and native setup. The npm cache override used successfully is `npm_config_cache="$PWD/.local/npm-cache"`.
- Only use isolated `run/saves/too-many-agents-development`; never personal `run/play`. Save/disconnect before stopping the verified JVM. Never manage/restart the user's BB installation.
- The previous giant plugin bundle/BB setup problem was fixed by loading only the pinned protocol data. Keep the server-bundle size and 4096-character tool-instruction guards; do not reintroduce all-version runtime data imports.
- No merge/release or complete-goal claim has been made. The full goal remains unfinished.
