# Mineflayer API port — handoff checklist

Updated October 7, 2026. **The full port is not complete. There is still implementation work, not just testing.** This is a practical checklist, not a percentage or a claim of exhaustive compatibility. The target remains [MINEFLAYER-PLAN.md](MINEFLAYER-PLAN.md).

## Exact checkpoint

- Branch: `feat/mineflayer-api`.
- Checkout: `/Users/scott/Else/too-many-agents/scratch/worktrees/mineflayer-api`.
- Latest built implementation: **`aad8063`**, bounded Pathfinder shortcuts/direct following, corrected ordinary-goal fallback, and body-addressed sounds. Full Java/plugin/package build passed; focused native shortcut/following and sound checks passed.
- Latest built artifact: `build/libs/too-many-agents-0.9.0.jar`; SHA-256 `20ddab6d627bcc857143a05378ed78c56f123a2ed87f4c325eec5eb34d629d5a`.
- The tester ran that exact new artifact for shortcut/following and sound checks. Previous performance measurements remain tied to **`042cc01`**, hash `2f97bf374d8c08acb8b903ecea0b5b7d296219741bb762399286c73ff5be8ce5`; they are not measurements of newer builds.
- Latest focused native pass: **`aad8063`**, straight/diagonal single-node shortcuts, direct cow following with no fallback and stationary hold, plus body-targeted and world sound delivery. The first script used a wrong name accessor and stopped before follow; only the unreached follow phase was corrected and run. Earlier presentation/creative and performance checks remain valid for their recorded builds.
- Previous test JVMs `83377`, `86485` and `13903` saved/disconnected with all dimensions saved, then stopped. `aad8063` JVM `25257` also saved/disconnected with all dimensions saved, then stopped. Inventory/equipment/selected slot matched the native baseline exactly. Lifecycle is back with the lead; no test client is running.
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
- [x] Player lists and UUID/name mapping; stable game state; time, health, oxygen, weather, XP and version feature queries.
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
- [x] Final Java build, plugin typecheck/bundle and packaging passed for `880c87b`.
- [x] Fishing action and body-aware hook adapter compile with Java 21; JavaScript syntax passes.
- [x] Fishing replacement interruption and script cleanup: the native hooks were removed and the rod remained unchanged (`fc483c4`).
- [x] Fishing corrected replacement sequence: distinct hooks, at most one active, predecessor rejected with the cancellation code; second attempt retrieved naturally. Bobber/line framebuffer inspected.
- [x] Rebuilt fishing check: salmon +1, rod damage 0→1, XP 1516→1517, hook removed and 20 further world/physics ticks advanced; independently verified native inventory/XP.
- [x] Boat forward/turn/zero-input coasting/manual controls and dismount verified. Native mobs may reboard a nearby boat after the ordinary 60-tick cooldown; move clear to remain dismounted.
- [ ] Exhaustive native conformance, all body species, all lifecycle combinations and paired live Mineflayer-server comparisons.

Key evidence (local BB thread storage):

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

## Still to implement or finish

- [x] **Fishing implementation:** scoped action, native bite/retrieval/loot, cancellation and a body-aware hook with native renderer.
- [x] **Fishing focused verification:** actual catch, loot/XP/durability, rendering and scoped cancellation; iterator stall fixed and retested.
- [x] **Boat steering implementation:** `moveVehicle` and manual inputs use the native boat controller, enforce controlling-seat ownership and clear inputs on release. Java/plugin/package build passed.
- [ ] **Vehicle steering verification and other mounts:** basic boat movement/turning/release passed; boundary and passenger-ownership edge cases remain unverified. Horses, pigs, striders and minecarts remain implementation work.
- [x] **Creative inventory implementation:** setInventorySlot/clearSlot/clearInventory use the existing operation queue, trusted component wire encoding, actual native slots and authoritative completion. Java/plugin/package build and 74 literal wire encoding fixtures passed. Packaged `4d7e8b9` passed a real named/damaged sword write and clear with unchanged unrelated inventory; wider component/permission cases remain unrun. Slot 0 rejects as a native output slot; slot 45 supports native offhand.
- [x] **Elytra flight implementation:** async elytraFly starts actual living-body gliding, preserves momentum through ordinary action completion, exposes water/flight state, and clears script-owned gliding on release. Java/plugin/package build passed. Native airborne gliding and scoped release passed on `042cc01`; special travel profiles remain pending.
- [x] **Native elytra rocket implementation:** activating a held rocket while gliding creates the native rocket attached to the actual body, with native item cost, physics and lifetime. Observations provide actual remaining boost ticks, usedFirework and entityElytraFlew events. Java/plugin/package build and the focused `042cc01` check passed: native attachment to the actual body, one rocket consumed, one event, boost/countdown and landing/release cleanup. Offhand, creative, multiple-rocket and failure edge cases remain unverified.
- [x] **Creative flight implementation:** startFlying/stopFlying/flyTo, bounded native collision steps, manual vertical input and scoped hover state; no persistent NoGravity edit. Java/plugin/package build and focused native arrival/hover/wall rejection/return passed.
- [x] **Creative focused verification:** packaged `4d7e8b9` passed component-bearing slot write/clear, unchanged prepared inventory, native flight arrival/hover, solid-wall rejection and grounded return. Seven requests, 53 updates, 18 bridge operations. Independent native inventory captured the transient named sword/damage7; native grounded/no persistent NoGravity cleanup passed. Original survival mode and exact inventory slots/components were restored.
- [ ] **Creative wider verification:** permission failures, clearInventory/offhand/persistence, release and cancellation edge cases remain unrun. Controls during another active action retain the existing busy fence.
- [x] **Bed/sleep/wake implementation:** actual body sleep, native bed occupancy, wake, parsed bed metadata and sleep/wake events; full build passed.
- [x] **Bed/sleep/wake focused verification:** actual sleeping body and occupied halves persisted after script release; sleep/wake handlers saw updated state; wake cleared occupancy, ticks continued, inventory stayed unchanged, already-awake rejected. Wider permissions/lifecycle cases remain pending. Native Mob sleep does not add a player or establish player respawn/night-skipping. Remaining body-specific gameplay stays pending.
- [x] **Chat and completion implementation:** chat/whisper, bounded per-script queues, public server chat and proxy-addressed messages, patterns, awaitMessage and tabComplete. Command sending retains creative_commands permissions; completion queries the native dispatcher without executing commands. Java/plugin/package build passed. Human-client private chat history is not copied.
- [x] **Chat focused verification:** packaged `53dd7ca` passed typed human/body chat, UUID identity, parsed patterns, whisper feedback and initial scoreboard/team hydration. `/tell D` returned Dev; unknown prefix was empty.
- [x] **Completion permission fix:** ordinary `/te` exposed privileged literals because raw Brigadier suggestions omit child permission checks. The adapter now filters providers with `canUse`; packaged `35e8a4f` removed privileged teleport/team/tellraw. Permitted GameTest `test` remains correctly visible because its native registration has no permission requirement. The first rebuilt script stopped only on an incorrect test exclusion; no completion rerun is needed.
- [ ] **Remaining chat sources:** broader message sources and lifecycle/overflow cases remain pending; body speech has no signed player identity.
- [x] **Complete column implementation:** native section bytes, actual biome registry IDs, block/sky light and client-visible block-entity tags feed the selected upstream ChunkColumn class. Exposes getColumn/getColumnAt/getColumns, load/unload events and a bounded complete 5×5 wait. Wider Pathfinder routes submit bounded state boxes from this cache. Java/plugin/package build and selected native checks below passed.
- [x] **Column focused offline verification:** selected upstream comparisons, malformed-wire checks and actual QuickJS passed. The complete production bot/runner hydrated 25 source-generated columns, read a remote block, retained column identity and processed two state updates within its normal limits. The changed Pathfinder integration passed 11 existing lifecycle scenarios. These are not Java-byte or live-world checks.
- [x] **Column native initialization smoke:** packaged `26aea35` received 25 complete columns; full-height local/remote samples, sync/async identity and saved sign tags passed. Independent native block/sign evidence matched. The corrected freshness check also passed: local guest mutation restored after one native tick; a remote block-change callback read updated state/light, with stable world/column references. Counts: one look request, 12 updates, five bridge operations, 1,147 ms. This is a small flat-world check, not worst-case performance evidence.
- [x] **Guest world-cache methods:** reused pinned World/WorldSync for local setters, initialize, async queries, columns, storage callbacks and save/unload queues. These change only guest cache state; no host storage or native authority is added. Corrected async ray geometry and Bot event forwarding remain integrated. Production QuickJS check passed for identity, sync/async mutations and hydrated events, initialization, in-memory save/deferred unload, remote raycasting and authoritative local refresh; zero native requests. Java/plugin/package build passed.
- [x] **Guest storage/native-cache integration:** native changes queue observed columns for explicitly supplied guest providers. Pending saves retain their column/provider through native unload; newer edits survive an awaited save; failed callbacks reject with work retained. Three focused production-runner cases passed with zero native requests. No disk/native persistence is implied.
- [ ] **World/chunk completion:** native wire/light/biome/event verification, server/guest frame-cost measurements and wider loaded-region coverage. Storage callbacks are guest-local and native persistence remains separate. Missing cells remain unknown. See [column scenarios](tools/mineflayer-reference/columns-native-scenarios.md).
- [x] **Read-only WorldSync additions:** block state/type/metadata/light/sky-light/biome getters and ordinary/coordinate block-update events. Unknown getters return null explicitly. Java/plugin/package build passed; runtime/native verification remains pending. Complete columns are supplied by the separate column implementation above.
- [ ] **Remaining Bot surface and semantic decisions:** `respawn()` is absent; native Mob death/respawn needs a defined contract. `waitForChunksToLoad()` is implemented and passed the packaged 25-column initialization check. Self `username`/`player`, hunger/saturation (user decision pending), public `physics`/`physicsEnabled`, settings and resource packs need applicability/implementation review. Body-addressed tab-list observations are implemented and passed their focused native check. Native firework state passed the focused flight/boost check. Do not invent player identity, hunger or client physics for a Mob. The read-only declaration audit is [recorded here](</Users/scott/.bb/thread-storage/thr_xykqkgui57/mineflayer-public-api-readonly-audit.json>); its spawnPoint and waitForChunksToLoad omissions are addressed by the current implementation.
- [x] **Observed entity state event implementation:** emits effect additions/changes/removals (excluding normal countdown), crouch transitions, self move and rotation-only entityMoved; stable effects map. Full Java/plugin/package build passed.
- [x] **Observed entity event focused verification:** one add/removal, stable objects through 280 countdown ticks, updated crouch/uncrouch callbacks and look-only move(previous Vec3). Amplifier refresh and another entity’s rotation remain unverified. Changes entirely between observations are not reconstructed.
- [x] **Scoreboard/team implementation:** stable maps, displayed objectives/scores, all public teams, formatting/member lookup, display slots and hydrated observed-change events. Native serialization is cached until scoreboard changes. Java/plugin/package build and focused native checks passed.
- [x] **Scoreboard/team focused verification:** packaged `53dd7ca` create/update/remove passed; callbacks saw updated fields, stable board/team/maps/keeper identities, then cleared maps/display slots on deletion. Counts for update/remove: 2/2 requests, 651 updates, 23 bridge operations. Changes between snapshots are not reconstructed.
- [x] **Native damage/item-pickup event implementation:** entityHurt and playerCollect are captured from native post-event callbacks, scoped to active nearby bodies, with bounded queues and event-time entity/item metadata. Final hydration precedes callbacks. Java/plugin/package build passed; native verification remains unrun.
- [x] **Native death event implementation:** nearby entityDead retains typed victim state and checks final native cancellation before delivery. Java/plugin/package build passed; accepted/canceled death checks remain unrun.
- [ ] **Native event verification:** accepted/canceled damage and death, pickup metadata/identity and cleanup await a focused fixture. The own-body XP/arrow take path is wired but unverified; other Mob collection and wider event sources remain pending.
- [x] **Sound observation implementation:** native positional/entity sounds, finalized cancellation/modification, native hearing range and bounded lease-scoped queues; callbacks receive typed Vec3 positions. The original world-sound path built successfully. Commit `b428595` adds body-proxy positional/entity packets in the same ordered queue, with native recipient selection and no invented source positions; Full Java/plugin/package build and the focused native packet/world-source check passed.
- [x] **Sound focused verification:** packaged `aad8063` passed far positional and actual-body entity packets plus a nearby native world note, in order with shared Vec3 values, expected volume/pitch, unchanged inventory and no replay. An unrelated proxy received a negative-control packet. One request, 17 updates, five bridge operations.
- [ ] **Sound wider verification and remaining sources:** targeted/range/cancellation/modification/lease edge cases beyond the focused fixture remain pending. Global level events and client-only sound sources remain pending.
- [x] **Boss-bar implementation:** shared BossBar class, stable observed list and hydrated creation/update/deletion events. Reads actual visible Wither events within the existing observation region, the End fight under its native distance predicate, and custom bars assigned to the interaction proxy. Fifty-six upstream comparisons plus flag/identity/lifecycle checks pass in QuickJS. Java/plugin/package build passed; native verification remains pending.
- [ ] **Boss-bar visibility/verification:** focused native fixture, wider Wither tracking and custom recipient targeting remain pending. No human-client boss-bar list is copied; no full network visibility claim.
- [x] **Biome metadata correction:** known Block.biome values now use the actual native-ID registry, fixing the pinned loader’s blank name/climate fallback. Unknown IDs retain the explicit fallback. Java/plugin/package build passed; packaged `53dd7ca` returned the native plains biome name.
- [x] **Particle payload class:** pinned Particle constructors, registry tables and current-version fromNetwork conversion are ported; 13 direct upstream comparisons and the Java/plugin/package build pass. Native server broadcast and proxy-targeted particle capture are now implemented with native range, bounded lease-scoped queues and typed Particle events. Compilation/typecheck/package build passed; packaged `53dadd0` verified a natural landing event: shared Particle/Vec3, block type, count 60, expected position, zero offsets and speed 0.15, without replay. Earlier failed fixtures were setup/timing mistakes. Targeted/range/multi-body cases remain unverified. Human-targeted and client-only particles are not copied.
- [x] **Entity animation/status implementation:** actual server swing/critical/magic-critical broadcasts and taming/tamed/shaking/grass-eating/hand-swap statuses use the existing bounded nearby event queue. Offhand animation is corrected to a swing; entityEat comes from completed native EAT/DRINK use. Java/plugin/package build passed. Packaged `53dadd0` verified exactly two same-body arm-swing events, no false eating from offhand swing, and one actual apple-eat event with the new inventory count already visible. Other status/critical signals remain unverified. Existing damage/death/wake sources are not duplicated.
- [x] **Title/tab-list implementation:** a send-only hook observes packets addressed to the body’s existing FakePlayer proxy. Stable typed `tablist` header/footer, title/subtitle/timing/clear and action-bar delivery use authoritative state and bounded queues. The proxy keeps its original no-op connection; no human UI state or player identity is copied. Java/plugin/package build and packaged `4d7e8b9` startup passed. Its focused native fixture delivered all five events in order, stable hydrated ChatMessages/tab-list, no replay and unchanged inventory, using one request/10 updates/five bridge operations. The fixture also sent an unrelated-recipient negative control. Broader lifecycle and replacement cases remain unverified. See [preauthored scenarios](tools/mineflayer-reference/presentation-native-scenarios.md).
- [ ] **Events and observations:** remaining collection sources, client-only particle sources, wider title/tab-list lifecycle checks and other public events/data. The pinned API has an explosion damage estimator, not a declared explosion event; do not invent an event under the compatibility target. Audit applicability; do not silently exclude them.
- [x] **Horse inventory implementation:** native sneak-interaction opens the real HorseInventoryMenu; HorseWindow uses actual saddle/armor/storage/player slots and ordinary generation/reach/cursor rules. Java/plugin/package build passed; native verification remains unrun. Mounted inventory-command access is still pending.
- [x] **Command-block editing implementation:** setCommandBlock retains synchronous void/control draining, native block-entity scheduling and flags; requires creative_commands, existing operator permission, enabled commands and reachable observed target. Java/plugin/package build passed; native verification remains unrun.
- [x] **Explosion estimate implementation:** getExplosionDamages uses observed body geometry and refuses unknown exposure. Eighteen pinned-source comparisons plus six correction/bound checks pass in QuickJS. It retains upstream's approximate reductions, not a guarantee of native damage. Java/plugin/package build passed.
- [ ] **Special menus/actions:** native verification of horse/command-block and existing specialized menus; remaining applicable hooks. Beacon-specific operations have no public method in pinned Mineflayer and remain an applicability-review item, not an invented API.
- [ ] **Inventory click modes 5/6:** drag/double-click remain explicitly unsupported, as in pinned upstream; make the compatibility decision explicit.
- [ ] **Pathfinder completion:** bounded shortcuts/free motion are integrated, built and passed straight/diagonal native shortcuts and direct cow pursuit/hold. Native preflight alone authorizes execution; only known preflight rejection permits fallback. Direct fluids/climbing, special species/effects, larger vertical/multiple-jump segments and wider dynamic/custom-goal/cancellation combinations remain pending; ordinary selected-edge planning stays available.
- [ ] **Permission/lifecycle coverage:** remaining cancellation, world/session change, stale handles, timeout and unknown-reply combinations across new APIs.
- [ ] **Complete member-by-member audit:** reconcile declarations, documentation, source and implementation, including dependency objects and ambiguous applicable APIs.
- [x] **Catalog refresh:** indexed six exact historical packaged scripts with verified scenario/report hashes, tested revisions and independent native summaries; added recent library candidates and guest registration source locations. No old native test was rerun or API promoted to fully supported.
- [ ] **Complete evidence reconciliation:** remaining historical runs, individual contract coverage and paired reference evidence still need review. Catalog pending counts remain conformance obligations, not an implementation percentage.
- [ ] **Paired live reference runs:** reference server is prepared, but Minecraft server EULA acceptance remains unanswered. Do not accept/start it without authorization.
- [x] **Performance comparison:** both recorded pairs passed on fixed `042cc01` with Codex 6.1 Sol low. Wall: 18 visible calls → 1; gather/craft: 44 → 1 with equal outputs and axe wear. The script used 31 native actions versus 21 directly for gathering/crafting; the saving is agent round trips, not fewer native operations. [Exact measurements and caveats](tools/mineflayer-reference/performance-scenarios.md) distinguish script/tool/coordination time. Independent native outcomes and restoration passed; the world was saved and exact test JVM stopped. [Combined evidence](</Users/scott/.bb/thread-storage/thr_xykqkgui57/performance-042cc01-summary.json>).
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

- Non-boat mounted controls are assigned to implementation child `thr_6qrtxjgv3m`, on a fresh branch from `5b15e8b`. Horses, pigs/striders and minecarts must retain native eligibility and physics; implementation and native checks are unfinished.
- Nearby Mob collection observation has a source implementation using actual native take notifications, with original item data retained across native extraction and duplicate body/player item notifications suppressed. It also replaces the body's separate XP/arrow callback with the shared native source. [Preauthored scenarios](tools/mineflayer-reference/mob-collection-native-scenarios.md); build and native verification remain pending.
- Test coordinator `thr_xykqkgui57` owns the next focused damage/death/body-item-pickup check on the existing `aad8063` artifact. That artifact does **not** contain the new nearby Mob collection observer or mount changes. No passing result is claimed yet.

## Working approach and logistics

- Keep porting first; run only important new/changed E2E cases. Do not repeat all passing suites or chase irrelevant coordinate precision. Wrong targets, item loss, false success and ownership failures still matter.
- Implementation children: BB threads, Astra as appropriate. Dedicated test coordinator: `thr_xykqkgui57` (Codex 6.1 Sol medium); in-world executor: `thr_tp9qyhq6ed` (Codex 6.1 Sol low). Check their current BB status before resuming. Never give overlapping lifecycle ownership.
- Test body UUID: `3793b7e5-d567-45f5-8767-8ca5151863a0`, bound to the in-world executor. Re-observe fixtures; do not assume historical positions/inventory.
- Use `tools/build build`, then separately `tools/build runPackagedClient -PpackagedJarRun -PdevWorld`. Follow AGENTS.md for exact-PID shutdown and native setup. The npm cache override used successfully is `npm_config_cache="$PWD/.local/npm-cache"`.
- Only use isolated `run/saves/too-many-agents-development`; never personal `run/play`. Save/disconnect before stopping the verified JVM. Never manage/restart the user's BB installation.
- The previous giant plugin bundle/BB setup problem was fixed by loading only the pinned protocol data. Keep the server-bundle size and 4096-character tool-instruction guards; do not reintroduce all-version runtime data imports.
- No merge/release or complete-goal claim has been made. The full goal remains unfinished.
