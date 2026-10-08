# Mineflayer API port — handoff checklist

Updated October 7, 2026. **Core gameplay is broadly implemented and selectively native-tested; the full compatibility goal remains unfinished.** This is a practical checklist, not a percentage or a claim of exhaustive compatibility. The target remains [MINEFLAYER-PLAN.md](MINEFLAYER-PLAN.md).

## Exact checkpoint

- Branch: `feat/mineflayer-api`.
- Checkout: `/Users/scott/Else/too-many-agents/scratch/worktrees/mineflayer-api`.
- Latest built implementation: **`a6d9474`**, adding documented chat-pattern inspection and current scripting guidance atop the native dominant-hand settings. Full Java/plugin/package build passed. Packaged pattern inspection passed with zero physical requests; reopening independently preserved the prior native inventory, equipment, selection and right handedness.
- Latest built artifact: `build/libs/too-many-agents-0.9.0.jar`; SHA-256 `e5035c0845beb03dfd4aed50c24ef04e8ae839cbbd9216f8618ec825ced435dc`.
- The earlier `aad8063` artifact passed shortcut/following, sound and accepted hurt/death/pickup checks; those passes do not verify the new additions. Previous performance measurements remain tied to **`042cc01`**, hash `2f97bf374d8c08acb8b903ecea0b5b7d296219741bb762399286c73ff5be8ce5`; they are not measurements of newer builds.
- Latest focused native pass: **`c244383`**, dominant-hand changes/invalid-option rejection/finish-drain restoration and healthy strider movement/release/dismount. Earlier `67ebb2c` passed Fox/body collection, username and basic horse/Pig/minecart controls. These selected cases do not verify every species, boost, jump or lifecycle combination.
- Lifecycle: latest `a6d9474` tester saved/disconnected with all dimensions saved and stopped exact JVM `95862`. Lifecycle is free; remaining native checks are held on executor availability. A harmless preparation turn returned READY, but the latest provider event still reported a blocked weekly subscription window. No client was launched for those checks. [Availability evidence](</Users/scott/.bb/thread-storage/thr_xykqkgui57/remaining-executor-availability-summary.json>). [Handback](</Users/scott/.bb/thread-storage/thr_xykqkgui57/a6d9474-summary.json>).
- Minecraft 1.21.1; Mineflayer 4.39.0; Pathfinder 2.4.5. Exact dependencies and source revisions: [upstream.json](tools/mineflayer-reference/upstream.json).

## Implemented

Checked boxes mean the described implementation exists. Verification is listed separately; **a checked group does not mean every member, event or edge case is fully conformant**.

### Script execution and state

- [x] One `minecraft_run` call executes async JavaScript, loops and sequences against an existing native body.
- [x] Isolated QuickJS worker, bounded memory/CPU/deadline/output; no guest filesystem, network, credentials or Node module loader.
- [x] Thread/body/world ownership, expiring control lease, cancellation and cleanup; unknown outcomes abort without automatic replay.
- [x] Ordered state stream; action promises wait for their authoritative state. Tick waits and synchronous control draining. Positive tick waits now drain preceding controls before counting frames; rebuilt native takeoff passed.
- [x] Stable Entity/Vec3 references, inventory/windows, held/equipped items and observed vehicle/passenger relationships.
- [x] Typed Block, Item, Window, Entity, ChatMessage/MessageBuilder, Recipe/RecipeItem and Vec3 objects; component/NBT transport.
- [x] Player lists and UUID/name mapping; `bot.username` reads the actual body name; the focused `67ebb2c` check matched native ScriptProbe; stable game state; time, health, oxygen, weather, XP and version feature queries.
- [x] Script-local `loadPlugin`/`loadPlugins`/`hasPlugin`, pinned protocol/major version and registry feature queries. Native shared spawn position is exposed as a stable `spawnPoint` Vec3. Java/plugin/package build passed; focused runtime/native checks remain pending.

### Observation and ordinary gameplay

- [x] Block/entity search, cursor queries, visibility checks and world raycasting against the observed cache.
- [x] Complete loaded 5×5 chunk-column observations, selected upstream ChunkColumn API, stable column references/load events and `waitForChunksToLoad`. Local observations update every frame; wider columns refresh within five ticks and after completed actions. No forced chunk loading or native mutation through guest column setters.
- [x] Look/lookAt, digging and stopping, dig estimates, block placement, block/entity activation.
- [x] Equip/unequip, quickbar selection, clicks, transfers, tossing and ordinary container operations.
- [x] Native crafting with actual outputs and remainders, including cake buckets; no synthetic recipe outputs.
- [x] Furnace-like, enchantment, anvil and villager window adapters; native properties, XP and trade outcomes.
- [x] Book writing/signing with native component preservation, identity checks and guarded slot restoration.
- [x] Attack/swing, item activation/release, eating and drinking on the actual body, native shield-use plumbing.
- [x] Entity-placement adapter; armor stands and boats have live checks. Other accepted item types need coverage.
- [x] Manual forward/back/strafe/jump/sprint/sneak controls, clear controls and mount/dismount.
- [x] Sign text updates after acquiring the native editor through interaction.
- [x] Observed `blockEntityData` and actual native editor `signOpen` events implemented with hydrated typed Blocks; Java/plugin/package build passed. Packaged `35e8a4f` passed two real editor openings and exactly one hydrated text/NBT update, with no unchanged-frame replay.

### Pathfinder

- [x] Reused upstream AStar/heap/Move/goals, with body-aware Movements and synchronous/resumable planning.
- [x] `goto`, `setGoal`, dynamic-goal plumbing, `setMovements`, stop, planning methods, best tool and movement/mining/building queries.
- [x] Native execution of selected route edges with collision/revision checks, explicit digs/placements and real inventory costs.
- [x] Ground routes, slabs/steps, jumps/gaps, drops, ladders, swimming, bridge placement and tower placement implemented.
- [x] Known upstream defects corrected explicitly rather than copying false success or lost actions.
- [x] Bounded shortcut execution and direct entity following implemented; surveyed candidates undergo native trajectory validation. Ordinary fallback preserves the supplied goal predicate; direct pursuit uses physical range. Fourteen focused source/QuickJS groups, the 31 existing planning scenarios and full build passed. Native straight/diagonal one-node routes and direct cow pursuit with stationary hold also passed; wider cases remain below.

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
- [x] Fishing replacement interruption and script cleanup: the native hooks were removed and the rod remained unchanged (`fc483c4`).
- [x] Fishing corrected replacement sequence: distinct hooks, at most one active, predecessor rejected with the cancellation code; second attempt retrieved naturally. Bobber/line framebuffer inspected.
- [x] Rebuilt fishing check: salmon +1, rod damage 0→1, XP 1516→1517, hook removed and 20 further world/physics ticks advanced; independently verified native inventory/XP.
- [x] Boat forward/turn/zero-input coasting/manual controls and dismount verified. Native mobs may reboard a nearby boat after the ordinary 60-tick cooldown; move clear to remain dismounted.
- [ ] Exhaustive native conformance, all body species, all lifecycle combinations and paired live Mineflayer-server comparisons.

Key evidence (local BB thread storage):

- [Independent accepted hurt/death/pickup outcomes and shutdown](</Users/scott/.bb/thread-storage/thr_xykqkgui57/entity-events-summary.json>)

- [Independent shortcut/following/sound outcomes and clean handback](</Users/scott/.bb/thread-storage/thr_xykqkgui57/aad8063-summary.json>)
- [Native selected shortcuts](</Users/scott/.bb/thread-storage/navigation-direct-evidence-21448fc8.json>), [direct following](</Users/scott/.bb/thread-storage/navigation-follow-evidence-8a295ae9.json>) and [sounds](</Users/scott/.bb/thread-storage/sounds-evidence-7608a9b5.json>)
- [Native presentation/creative and exact restoration](</Users/scott/.bb/thread-storage/thr_xykqkgui57/4d7e8b9-summary.json>)
- [Native elytra/rocket, control ordering and cleanup](</Users/scott/.bb/thread-storage/thr_xykqkgui57/042cc01-summary.json>)

- [Essential packaged workflows](</Users/scott/.bb/thread-storage/thr_xykqkgui57/packaged-essential-summary.json>)
- [Persistence, combat and entity placement](</Users/scott/.bb/thread-storage/thr_xykqkgui57/combat-focused-summary.json>)
- [Movement, jump, eating and shield use](</Users/scott/.bb/thread-storage/thr_xykqkgui57/eede028-focused-summary.json>)
- [Final fixes, signs and player/game state](</Users/scott/.bb/thread-storage/thr_xykqkgui57/880c87b-final-focused-summary.json>)
- [Independent native columns, freshness and clean shutdown](</Users/scott/.bb/thread-storage/thr_xykqkgui57/26aea35-columns-summary.json>)
- [Independent chat and scoreboard results](</Users/scott/.bb/thread-storage/thr_xykqkgui57/53dd7ca-chat-scoreboard-summary.json>)
- [Native chat/patterns and completion finding](</Users/scott/.bb/thread-storage/chat-scoreboard-evidence-14e43f51.json>) and [scoreboard/team update/removal](</Users/scott/.bb/thread-storage/scoreboard-remaining-evidence-f964a2f9.json>)
- [Completion/sign results and clean shutdown](</Users/scott/.bb/thread-storage/thr_xykqkgui57/35e8a4f-summary.json>)
- [Independent signal/particle results and shutdown](</Users/scott/.bb/thread-storage/thr_xykqkgui57/53dadd0-summary.json>)
- [Native swing/consumption/particle pass](</Users/scott/.bb/thread-storage/entity-signals-particle-evidence-cadc59b1.json>)
- [Rebuilt completion](</Users/scott/.bb/thread-storage/completion-sign-particle-evidence-758c7c25.json>) and [sign events](</Users/scott/.bb/thread-storage/sign-particle-remaining-evidence-dd028758.json>); [particle-only timing-limited attempt](</Users/scott/.bb/thread-storage/particle-only-evidence-bbc6610a.json>)
- [Native full-column reads](</Users/scott/.bb/thread-storage/full-columns-evidence-8339c5ce.json>) and [corrected freshness](</Users/scott/.bb/thread-storage/column-freshness-corrected-evidence-5706b6cd.json>)
- [Chat setup blocked by another connected game](</Users/scott/.bb/thread-storage/thr_xykqkgui57/bec7958-chat-blocked-summary.json>)
- [Fishing replacement/retrieval/rendering and historical iterator stall](</Users/scott/.bb/thread-storage/thr_xykqkgui57/fc483c4-corrected-fishing-summary.json>)

[Fishing fix and boat steering evidence](</Users/scott/.bb/thread-storage/thr_xykqkgui57/8bfa67e-fishing-boat-summary.json>). [Sleep evidence](</Users/scott/.bb/thread-storage/bed-sleep-corrected-evidence-99c6c629.json>), [wake evidence](</Users/scott/.bb/thread-storage/bed-wake-evidence-74c649ec.json>). [Independent bed evidence](</Users/scott/.bb/thread-storage/thr_xykqkgui57/6ee4bb3-bed-summary.json>). [Observed events evidence](</Users/scott/.bb/thread-storage/thr_xykqkgui57/3875c0b-observation-summary.json>).

Earlier failed mount/potion checks are superseded by the final successful build. Post-script shield release was expected cleanup, not a production defect. Several test failures were fixture mistakes; do not treat every historical failure as an unresolved product bug.

### Further implemented groups

- [x] Native fishing, cancellation/replacement, catch/loot/XP/rod wear and bobber rendering. The historical entity-iterator stall was fixed and a natural catch plus continued ticking passed.
- [x] Boat, horse-family, Pig/Strider and minecart controls. Basic boat/horse/Pig/Strider/minecart movement, actual passenger identity, release and dismount passed. Native coasting and Pig/Strider always-forward behavior remain intact.
- [x] Creative component-bearing inventory edits and bounded collision-aware flight/hover; focused write/clear, obstruction and grounded cleanup passed.
- [x] Native elytra flight and attached rocket use, consumption, countdown and landing cleanup passed.
- [x] Bed sleep/wake and occupancy, including sleep retained after script release; focused events and already-awake rejection passed.
- [x] Chat/whisper/patterns/completion, scoreboard/team maps and events. Selected native delivery, permission filtering and update/removal passed.
- [x] Guest World/WorldSync methods and storage callbacks; native authority restores guest-only edits. Complete columns, light/biome/sign metadata, stable references and selected native freshness passed. Missing cells remain unknown; no forced chunk loading.
- [x] Effect/crouch/movement, accepted hurt/death and body/Fox collection events; selected native callbacks saw hydrated state and stable typed identities, with no replay.
- [x] Native sound, particle, swing/eat and proxy-addressed title/tab-list observations; focused native fixtures passed. Human-client private presentation is not copied.
- [x] BossBar class and native observation sources; library comparisons/build passed, native visibility check pending.
- [x] Horse inventory and permission-checked command-block editing; build passed, native checks pending. Mounted horse inventory-command access remains pending.
- [x] Explosion damage estimate using observed geometry, refusing unknown exposure; source comparisons/build passed. This remains an estimate, not native damage authority.
- [x] Native dominant-hand settings: stable observed bot.settings.mainHand, synchronous queued setSettings and aligned interaction proxy. Full c244383 build and selected native setting/finish-drain checks passed; wider persistence remains unverified. Unsupported/mixed client settings reject before mutation.
- [x] Inventory click modes 5/6 retain the pinned upstream's documented unsupported failures. Deprecated GoalBreakBlock follows the documented/source wrapper, with its conflicting TypeScript inheritance recorded.
- [x] Shared gather procedure passed on the authorized isolated reference server and matched historical native outcomes. Wider reference outcomes below remain incomplete.
- [x] Performance comparisons on fixed 042cc01: wall 18 agent calls→1 script; gather/craft 44→1, with matching native outcomes. Script execution 1.225s/8.915s; direct tool intervals 123.557s/235.226s include model coordination (wall also compaction). This measures fewer agent round trips, not faster native physics. [Evidence](</Users/scott/.bb/thread-storage/thr_xykqkgui57/performance-042cc01-summary.json>).

- [x] Documented chatPatterns inspection records and legacy description retention. Pinned runtime omits the documented field; this correction preserves existing registration, sequential matching and removal. Packaged a6d9474 inspection passed descriptions, sequential matching, retirement and removal with unchanged inventory. These synthetic script-local messages verify the guest surface, not native chat delivery. [Evidence](</Users/scott/.bb/thread-storage/pattern-inspection-evidence-a39643f9.json>).

## Still to implement or finish

- [x] **Healthy strider verification:** corrected clear-weather/dry fixture passed 1.735 blocks of native movement, actual body passenger, release/dismount and unchanged inventory on `c244383` (7/7 requests, 77 updates).
- [ ] **Remaining vehicle verification:** boost activation, horse jumps, wider rail/bounds and ownership cases remain unverified.
- [ ] **Creative wider verification:** permission failures, clearInventory/offhand/persistence, release and cancellation edge cases remain unrun. Controls during another active action retain the existing busy fence.
- [ ] **Remaining chat sources:** broader message sources and lifecycle/overflow cases remain pending; body speech has no signed player identity.
- [ ] **World/chunk completion:** native wire/light/biome/event verification, server/guest frame-cost measurements and wider loaded-region coverage. Storage callbacks are guest-local and native persistence remains separate. Missing cells remain unknown. See [column scenarios](tools/mineflayer-reference/columns-native-scenarios.md).
- [ ] **Remaining Bot surface verification:** `respawn()` and player hunger/saturation are inapplicable under the user-confirmed native Mob contract; no substitute player or new survival mechanics will be added. `waitForChunksToLoad()` is implemented and passed the packaged 25-column initialization check. Self `player`, player-predictor `physics`/`physicsEnabled` and resource-pack negotiation are inapplicable to the chosen native Mob/connection contract. Dominant-hand settings passed selected native observation and synchronous finish-drain checks on `c244383`; wider persistence cases remain pending; unsupported client-only settings reject explicitly. Body-addressed tab-list observations are implemented and passed their focused native check. Native firework state passed the focused flight/boost check. Do not invent player identity, hunger or client physics for a Mob. The read-only declaration audit is [recorded here](</Users/scott/.bb/thread-storage/thr_xykqkgui57/mineflayer-public-api-readonly-audit.json>); its spawnPoint and waitForChunksToLoad omissions are addressed by the current implementation.
- [ ] **Wider native event verification:** canceled/immune damage and death, XP/arrow and other Mob pickup paths remain unverified. Fox/body collection also passed on `67ebb2c`; other collection paths remain unchecked.
- [ ] **Sound wider verification and remaining sources:** targeted/range/cancellation/modification/lease edge cases beyond the focused fixture remain pending. Pinned sound.js does not emit this event for level-event or client-generated audio; no extra renderer source is required.
- [ ] **Boss-bar visibility/verification:** focused native fixture, wider Wither tracking and custom recipient targeting remain pending. No human-client boss-bar list is copied; no full network visibility claim.
- [ ] **Events and observations:** remaining collection sources, wider title/tab-list lifecycle checks and other public events/data. Pinned particle.js only handles world_particles packets, so client-generated effects need no extra renderer observer. The pinned API has an explosion damage estimator, not a declared explosion event; do not invent an event under the compatibility target. Audit applicability; do not silently exclude them.
- [ ] **Special menus/actions:** native verification of horse/command-block and existing specialized menus; remaining applicable hooks. Horse-window, command-block and boss-bar scripts are prepared and syntax-checked only; [fixture notes](tools/mineflayer-reference/remaining-native-fixtures.md). Beacon-specific operations have no public method in pinned Mineflayer and remain an applicability-review item, not an invented API.
- [ ] **Pathfinder completion:** bounded shortcuts/free motion are integrated, built and passed straight/diagonal native shortcuts and direct cow pursuit/hold. Native preflight alone authorizes execution; only known preflight rejection permits fallback. Direct fluids/climbing, special species/effects, larger vertical/multiple-jump segments and wider dynamic/custom-goal/cancellation combinations remain pending; ordinary selected-edge planning stays available.
- [ ] **Permission/lifecycle coverage:** remaining cancellation, world/session change, stale handles, timeout and unknown-reply combinations across new APIs.
- [ ] **Raw Entity metadata:** current hydration fills custom-name index2 and dropped-item index8 only. Native flags and species-specific serializer values are not yet projected into the public metadata array. Ordinary observed fields remain available. Source review confirmed a bounded native-wire path through the existing host codec; implementation is in progress. Sparse initialization and observed default resets must be preserved. This is an implementation gap, not merely unrun coverage. [Design findings](</Users/scott/.bb/thread-storage/thr_6qrtxjgv3m/entity-metadata-feasibility.md>).
- [ ] **Complete member-by-member audit:** dependency objects, data and events still need final reconciliation. A bounded public gameplay-method/options audit found no additional concrete omission beyond the listed native-body decisions. [Exact source findings](</Users/scott/.bb/thread-storage/thr_6qrtxjgv3m/gameplay-method-audit.md>). Deprecated GoalBreakBlock follows its documented/source wrapper constructor; the contradictory inherited TypeScript declaration is recorded explicitly.
- [ ] **Complete evidence reconciliation:** remaining historical runs, individual contract coverage and paired reference evidence still need review. Catalog pending counts remain conformance obligations, not an implementation percentage.
- [ ] **Wider reference comparison:** wall produced the correct saved eight-block/zero-material outcome but failed the upstream immediate inventory assertion. Gather/craft failed with saved planks7 rather than native planks 4/sticks 8. Both failures and the upstream startup decode warning are retained; no false conformance pass or successful native rerun.
- [ ] **Final full-scope packaged/persistence validation** after remaining ports. Recent changes have focused verification, not a new exhaustive persistence cycle.

## Agreed boundaries

The user accepted Minecraft's EULA and chose native Mob behavior. Keep actual health/air/effects/item use and movement; do not add player hunger/saturation, player respawn, self-player metadata or client player-physics emulation. Connection/account setup, socket end/quit, resource-pack negotiation and the internal packet client have no independent per-body counterpart. Scripts never disconnect the shared game or BB. Multiplayer and arbitrary third-party plugin compatibility are not established. Other uncertain APIs remain pending review rather than automatically excluded.

The [catalog guide](tools/mineflayer-reference/README.md) and coverage.json retain individual obligations. Its strict pending counts are **not an implementation percentage**: broad API members are not promoted from isolated successful fixtures. The bounded [gameplay-method audit](</Users/scott/.bb/thread-storage/thr_6qrtxjgv3m/gameplay-method-audit.md>) found no additional concrete method omission beyond known categories; this does not establish exhaustive conformance.

## Latest affected findings

- **67ebb2c** Fox/body collection and username passed. Horse moved about 9.32 blocks, corrected Pig 2.329 and minecart 1.299, with release/dismount and unchanged inventory. [Horse](</Users/scott/.bb/thread-storage/vehicle-horse-evidence-a92be0bc.json>), [Pig](</Users/scott/.bb/thread-storage/vehicle-pig-corrected-evidence-c46e91b3.json>), [minecart](</Users/scott/.bb/thread-storage/vehicle-minecart-evidence-c9085b24.json>), [collection](</Users/scott/.bb/thread-storage/mob-collection-evidence-5771d89d.json>).
- **Historical strider fixture failure:** movement passed before native health 19→5→disappearance. Retained autosave rain countdown plus native wet-damage source support rain-induced fixture death; the original stream exception was not retained. No speculative production repair. The healthy dry clear-weather fixture later passed on c244383; retain the earlier failure without a product-regression claim. [Diagnosis](</Users/scott/.bb/thread-storage/thr_6qrtxjgv3m/strider-67ebb2c-diagnosis.md>).
- **Reference differences remain explicit:** exact wall procedure failed its immediate inventory assertion despite independently confirmed saved eight blocks/zero stone. Gather/craft failed with saved axe damage 2/planks 7 instead of native planks 4/sticks 8. Upstream startup logged ArmorTrimMaterial PartialReadError. No schema patch, false reference pass or repeat of successful native phases. Reports are under ignored run/mineflayer-reference; details in its README.
- **Settings:** [observe-settings.js](tools/mineflayer-reference/observe-settings.js) passed right→left, stable settings/inventory and invalid-option rejection; native LeftHanded matched and Dev was unchanged. Separate synchronous restoration completed through finish draining. The a6d9474 reopen independently preserved right handedness and all inventory/equipment/selection; wider settings cases remain unrun. [Setting evidence](</Users/scott/.bb/thread-storage/settings-evidence-12d18756.json>), [restoration](</Users/scott/.bb/thread-storage/settings-restore-evidence-6e0d2eb1.json>), [healthy strider](</Users/scott/.bb/thread-storage/healthy-strider-evidence-b64c78d1.json>).

## Working approach and logistics

- Keep porting first; run only important new/changed E2E cases. Do not repeat all passing suites or chase irrelevant coordinate precision. Wrong targets, item loss, false success and ownership failures still matter.
- Implementation children: BB threads, Astra as appropriate. Dedicated test coordinator: `thr_xykqkgui57` (Codex 6.1 Sol medium); in-world executor: `thr_tp9qyhq6ed` (Codex 6.1 Sol low). Check their current BB status before resuming. Never give overlapping lifecycle ownership.
- Test body UUID: `3793b7e5-d567-45f5-8767-8ca5151863a0`, bound to the in-world executor. Re-observe fixtures; do not assume historical positions/inventory.
- Use `tools/build build`, then separately `tools/build runPackagedClient -PpackagedJarRun -PdevWorld`. Follow AGENTS.md for exact-PID shutdown and native setup. The npm cache override used successfully is `npm_config_cache="$PWD/.local/npm-cache"`.
- Only use isolated `run/saves/too-many-agents-development`; never personal `run/play`. Save/disconnect before stopping the verified JVM. Never manage/restart the user's BB installation.
- The previous giant plugin bundle/BB setup problem was fixed by loading only the pinned protocol data. Keep the server-bundle size and 4096-character tool-instruction guards; do not reintroduce all-version runtime data imports.
- No merge/release or complete-goal claim has been made. The full goal remains unfinished.
