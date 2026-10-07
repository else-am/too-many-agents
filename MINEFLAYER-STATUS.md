# Mineflayer API port — handoff checklist

Updated October 7, 2026. **The full port is not complete. There is still implementation work, not just testing.** This is a practical checklist, not a percentage or a claim of exhaustive compatibility. The target remains [MINEFLAYER-PLAN.md](MINEFLAYER-PLAN.md).

## Exact checkpoint

- Branch: `feat/mineflayer-api`.
- Checkout: `/Users/scott/Else/too-many-agents/scratch/worktrees/mineflayer-api`.
- Latest focused live-validated implementation: **`3875c0b`** (observed events); sleep/wake passed on `6ee4bb3`; fishing/boat controls passed on `8bfa67e`, earlier broad workflows on `880c87b`.
- Built artifact: `build/libs/too-many-agents-0.9.0.jar`.
- Latest validated JAR SHA-256: `29f49ed99c7aea61150198991a9dddcfa392591a3a4c2fe7ef56f1c4889b87c2`.
- Test JVM `85181` saved/disconnected successfully and was stopped after the blocked chat setup. Root owns lifecycle. Sleep/wake passed its focused native check on `6ee4bb3`; observed entity events passed on `3875c0b`; chat/whisper/pattern source builds but remains native-unverified. Chat testing on `bec7958` was blocked before installation because another game was connected to BB; the installed scripting bundle differs. No chat scenario ran and no shared plugin was replaced.
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
- [x] Rebuilt fishing check: salmon +1, rod damage 0→1, XP 1516→1517, hook removed and 20 further world/physics ticks advanced; independently verified native inventory/XP.
- [x] Boat forward/turn/zero-input coasting/manual controls and dismount verified. Native mobs may reboard a nearby boat after the ordinary 60-tick cooldown; move clear to remain dismounted.
- [ ] Exhaustive native conformance, all body species, all lifecycle combinations and paired live Mineflayer-server comparisons.

Key evidence (local BB thread storage):

- [Essential packaged workflows](</Users/scott/.bb/thread-storage/thr_xykqkgui57/packaged-essential-summary.json>)
- [Persistence, combat and entity placement](</Users/scott/.bb/thread-storage/thr_xykqkgui57/combat-focused-summary.json>)
- [Movement, jump, eating and shield use](</Users/scott/.bb/thread-storage/thr_xykqkgui57/eede028-focused-summary.json>)
- [Final fixes, signs and player/game state](</Users/scott/.bb/thread-storage/thr_xykqkgui57/880c87b-final-focused-summary.json>)
- [Chat setup blocked by another connected game](</Users/scott/.bb/thread-storage/thr_xykqkgui57/bec7958-chat-blocked-summary.json>)
- [Fishing replacement/retrieval/rendering and historical iterator stall](</Users/scott/.bb/thread-storage/thr_xykqkgui57/fc483c4-corrected-fishing-summary.json>)

[Fishing fix and boat steering evidence](</Users/scott/.bb/thread-storage/thr_xykqkgui57/8bfa67e-fishing-boat-summary.json>). [Sleep evidence](</Users/scott/.bb/thread-storage/bed-sleep-corrected-evidence-99c6c629.json>), [wake evidence](</Users/scott/.bb/thread-storage/bed-wake-evidence-74c649ec.json>). [Independent bed evidence](</Users/scott/.bb/thread-storage/thr_xykqkgui57/6ee4bb3-bed-summary.json>). [Observed events evidence](</Users/scott/.bb/thread-storage/thr_xykqkgui57/3875c0b-observation-summary.json>).

Earlier failed mount/potion checks are superseded by the final successful build. Post-script shield release was expected cleanup, not a production defect. Several test failures were fixture mistakes; do not treat every historical failure as an unresolved product bug.

## Still to implement or finish

- [x] **Fishing implementation:** scoped action, native bite/retrieval/loot, cancellation and a body-aware hook with native renderer.
- [x] **Fishing focused verification:** actual catch, loot/XP/durability, rendering and scoped cancellation; iterator stall fixed and retested.
- [x] **Boat steering implementation:** `moveVehicle` and manual inputs use the native boat controller, enforce controlling-seat ownership and clear inputs on release. Java/plugin/package build passed.
- [ ] **Vehicle steering verification and other mounts:** basic boat movement/turning/release passed; boundary and passenger-ownership edge cases remain unverified. Horses, pigs, striders and minecarts remain implementation work.
- [x] **Creative inventory implementation:** setInventorySlot/clearSlot/clearInventory use the existing operation queue, trusted component wire encoding, actual native slots and authoritative completion. Java/plugin/package build and 74 literal wire encoding fixtures passed; native checks remain unrun. Slot 0 rejects as a native output slot; slot 45 supports native offhand.
- [ ] **Creative inventory verification and flight:** native creative permission/components/clear/persistence checks remain unrun. startFlying/stopFlying/flyTo remain implementation work. Existing physical creative_item is not the full API.
- [x] **Bed/sleep/wake implementation:** actual body sleep, native bed occupancy, wake, parsed bed metadata and sleep/wake events; full build passed.
- [x] **Bed/sleep/wake focused verification:** actual sleeping body and occupied halves persisted after script release; sleep/wake handlers saw updated state; wake cleared occupancy, ticks continued, inventory stayed unchanged, already-awake rejected. Wider permissions/lifecycle cases remain pending. Native Mob sleep does not add a player or establish player respawn/night-skipping. Remaining body-specific gameplay stays pending.
- [x] **Chat and completion implementation:** chat/whisper, bounded per-script queues, public server chat and proxy-addressed messages, patterns, awaitMessage and tabComplete. Command sending retains creative_commands permissions; completion queries the native dispatcher without executing commands. Java/plugin/package build passed. Human-client private chat history is not copied.
- [ ] **Chat verification and remaining sources:** native chat/completion checks are blocked while another game uses the shared plugin. No chat scenario ran. Broader server message sources and lifecycle/overflow cases remain pending; body speech has no signed player identity.
- [ ] **World/chunk API:** remaining applicable world/chunk methods and load events. Current synchronous observations cover a bounded **33×17×33** region; missing/unloaded cells are not known air.
- [x] **Observed entity state event implementation:** emits effect additions/changes/removals (excluding normal countdown), crouch transitions, self move and rotation-only entityMoved; stable effects map. Full Java/plugin/package build passed.
- [x] **Observed entity event focused verification:** one add/removal, stable objects through 280 countdown ticks, updated crouch/uncrouch callbacks and look-only move(previous Vec3). Amplifier refresh and another entity’s rotation remain unverified. Changes entirely between observations are not reconstructed.
- [ ] **Events and observations:** remaining native damage/animation/collection, sound/particle/explosion, spawn, scoreboard/team/boss-bar/title/tab-list and other public events/data. Audit applicability; do not silently exclude them.
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

## Fishing implementation and resolved stall

Implementation files:

- `bb-plugin/scripting/actions.mjs`: async `bot.fish()` with scoped start/wait/cancel and replacement handling, following the existing consume lifecycle.
- `src/main/java/toomanyagents/AgentHands.java`: begin/tick/cancel fishing through the existing native interaction proxy; native rod use handles retrieval, loot, XP and durability.
- `src/main/java/toomanyagents/AgentActions.java`: bounded `fish` action and terminal cleanup.
- `src/main/resources/META-INF/accesstransformer.cfg`: access to native `FishingHook.biting`, avoiding guessed particle/timer completion.
- `src/main/java/toomanyagents/BodyFishingHook.java`: registered native-hook subclass, body self-collision exclusion, native projectile launch, and a render-only client owner adapter.
- `TooManyAgents.java` registers the hook/renderer; `Observations.java` exposes its familiar `fishing_bobber` name.
- [Prewritten scenarios](tools/mineflayer-reference/fishing-native-scenarios.md).

The player-owner constraint now has an implementation: the server keeps the existing body-owned interaction proxy, while a client-only, unregistered render adapter follows the actual body. No network player or account is created. The hook excludes the visible body/vehicle from its own collision and retains native fishing timing, loot, XP and rod damage. Its launch uses Minecraft's projectile helper rather than copying exact player casting coordinates.

**Earlier failure:** the corrected `fc483c4` test passed cancellation/replacement and natural retrieval, then the server stopped advancing during pickup. Cancellation errors use `error.code === "FishingAborted"`, with `name === "ActionError"`; upstream itself uses a plain Error. The first test incorrectly asserted the name and stopped before a catch. [First-run evidence](</Users/scott/.bb/thread-storage/thr_xykqkgui57/fc483c4-fishing-summary.json>).

**Resolved blocker, fix committed as `6e293c7` (build and live catch/pickup passed):** the server thread spun in `GameAccess.tick` while iterating the live entity collection. Fishing changes that collection by removing hooks and adding loot. The fix collects bodies before executing their actions, so world mutations cannot invalidate the active iterator. [Thread dump](</Users/scott/.bb/thread-storage/thr_xykqkgui57/fc483c4-corrected-thread-dump.txt>).

**Verified:** `8bfa67e` completed a natural catch and continued ticking after pickup. No fishing rerun is needed unless its implementation changes. Rendering/replacement evidence comes from the earlier focused run; loot/XP/durability and the stall fix from the rebuilt run.

## Additional work in progress

Boat steering source was added after the fishing build: `actions.mjs`, `AgentActions.java`, a `Boat.controlBoat()` access transformer, and [prewritten scenarios](tools/mineflayer-reference/vehicle-native-scenarios.md). It uses native steering/physics, requires the actual body to control the boat, clears inputs on release, and enforces loaded/world/body-box boundaries. **Java/plugin/package build and basic live steering passed.** Other mount types and the listed boundary/ownership cases remain pending. Sleep/wake passed its focused live check; see [its scenarios](tools/mineflayer-reference/beds-native-scenarios.md).

## Working approach and logistics

- Keep porting first; run only important new/changed E2E cases. Do not repeat all passing suites or chase irrelevant coordinate precision. Wrong targets, item loss, false success and ownership failures still matter.
- Implementation children: BB threads, Astra as appropriate. Dedicated test coordinator: `thr_xykqkgui57` (Codex 6.1 Sol medium); in-world executor: `thr_tp9qyhq6ed` (Codex 6.1 Sol low). Check their current BB status before resuming. Never give overlapping lifecycle ownership.
- Test body UUID: `3793b7e5-d567-45f5-8767-8ca5151863a0`, bound to the in-world executor. Re-observe fixtures; do not assume historical positions/inventory.
- Use `tools/build build`, then separately `tools/build runPackagedClient -PpackagedJarRun -PdevWorld`. Follow AGENTS.md for exact-PID shutdown and native setup. The npm cache override used successfully is `npm_config_cache="$PWD/.local/npm-cache"`.
- Only use isolated `run/saves/too-many-agents-development`; never personal `run/play`. Save/disconnect before stopping the verified JVM. Never manage/restart the user's BB installation.
- The previous giant plugin bundle/BB setup problem was fixed by loading only the pinned protocol data. Keep the server-bundle size and 4096-character tool-instruction guards; do not reintroduce all-version runtime data imports.
- No merge/release or complete-goal claim has been made. The full goal remains unfinished.
