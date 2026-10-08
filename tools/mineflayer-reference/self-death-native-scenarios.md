# Self-death terminal delivery

Preauthored before implementation. Native kill checks remain UNRUN; they require a separate disposable bound body and are excluded from the current saved-body observation batch.

- While a script owns an existing living body, register health and death listeners, then wait. Use a separate disposable bound fixture body and cause an actual accepted native death; never kill the saved ScriptProbe body or its inventory. The last authoritative frame must hydrate health <= 0 and alive/isAlive false before health() then exactly one zero-argument death(). No respawn or synthetic health. Event logging must reach the caller before the stream's terminal body_dead error.
- Include a pending action: its interrupted terminal sequence and verified progress must be in the terminal frame. A listener attempting another mutation must be rejected because the lease/body is dead; notification never restores control. Unknown action outcomes must retain fail-closed behavior.
- Duplicate unchanged dead state must not emit another death. A first already-dead observation must not manufacture a live-to-dead edge. Health changes while alive must not emit death.
- Generic unload, world departure, dimension replacement and lease cancellation must not emit death. A canceled death attempt with a living body must not emit death.
- If terminal snapshot serialization fails, retain an error and close; do not invent a health value, partial frame or successful death callback. A full/closed stream cannot promise delivery.

Focused non-native check: use the actual runner/QuickJS worker with the actual state adapter, send an authoritative dead frame, await the runner's frame acknowledgement, then terminate the stream with body_dead. Assert hydrated health/death log ordering and zero arguments survive the terminal failure. This verifies the guest acknowledgement boundary, not a live Mob kill or native snapshot construction. No changes to host/runner are planned unless inspection identifies an ordering defect.

## Delivery contract and focused evidence

The terminal path captures only the original body in the current server/world/dimension/session, with actual dead health and no generic discard/unload/transfer removal reason. Mutation authority is revoked first. Normal action/hand cleanup precedes the snapshot; columns and event queues survive until capture. The narrow AgentHands observation overload requires an already-closed proxy and dead same-level body; ordinary syncBody/action guards remain unchanged. Snapshot/cleanup errors close the stream without synthesizing data.

Focused actual runner/QuickJS check passed with the actual state adapter: alive health change, health0/death in order with zero arguments and hydrated values, no repeat on a second dead frame, then terminal body_dead failure retaining logs. Java21 standalone compilation passed. Native snapshot construction/cleanup and kill delivery remain UNRUN.

Integration race identified from source: heartbeat or a newly submitted awaitAction can receive a dead-body lookup failure on an independent HTTP request before the ordered stream's terminal frame is acknowledged. The follow-up below now defers these structured known rejections to stream termination. Unknown/lost/malformed outcomes still abort without retry.

## Preauthored host race follow-up

Preserve structured native error codes through ApiError.nativeCode. During an outstanding stream call, whitelist only body_missing_or_unloaded and script_no_longer_controls_body under minecraft_action_failed. Such start/await/cancel/stop failures wait for existing request abort; heartbeat failures stop heartbeats and gate subsequent dispatch. No additional native request, guest catch/continuation, or synthesized state is allowed. The existing stream failure/cancellation/deadline ends the wait. All other outcomes retain their existing failure behavior, including immediate abort for unknown transport/invalid acknowledgements.

One focused host/runner probe uses actual minecraft.ts response decoding, minecraftScripts and QuickJS runner with controlled HTTP responses. Deliver a known terminal read rejection before the dead frame, then the frame and terminal error: callback logs must survive and no guest catch/new mutation may occur. Contrast an unknown lost reply with a delayed final frame: abort immediately without replay. Include the heartbeat gate and late await variants within this same transport scenario; these are not native death tests.

Result: the actual host/runner probe passed known-start (body_missing_or_unloaded), known-await and known-heartbeat (script_no_longer_controls_body) races: health/death logs arrived with hydrated values before body_dead termination, no guest catch/retry, and one scoped release. Unknown lost start reply aborted before the delayed frame, with no second action. The fixture used actual minecraftWorlds HTTP decoding, minecraftScripts, runner/worker and state adapter; only HTTP responses and the small guest caller were controlled. No native execution or full build was run for this follow-up.

## Prepared disposable fixture (source baseline 54ce6a4; NOT RUN)

`observe-self-death.js` is prepared and syntax-parsed, never executed here.
The accepted fixture mechanism is now vanilla
`data merge entity <exact NEW disposable UUID> {Health:0.0f}`. This is authorized
only as preparation for a separately authorized coordinator run, not a live
operation now, guest capability, or permission to mutate ScriptProbe.

**This establishes NBT-induced native terminal death, not accepted damage or
combat death.** Normal /kill remains canceled by `TooManyAgents.incomingDamage`
(161–165); the script's introductory /kill warning still applies. No immunity
or identity tags are changed. Do not remove binding tags, introduce a helper,
or use a broad selector.

Narrow generated 1.21.1 source verification:
- `DataCommands.mergeData:436–445` copies the existing entity NBT and merges only
  the supplied Health field. `EntityDataAccessor.setData:45–51` rejects Players,
  loads the same Entity object, and restores its UUID. `Entity.saveWithoutId:1721`
  and `Entity.load:1803` retain NeoForgeData, including the body's binding tags.
- `LivingEntity.readAdditionalSaveData:747–748` calls `setHealth`; its clamp at
  1105–1106 permits zero. `isDeadOrDying:1109–1110` and `isAlive:1595–1596` then
  expose actual dead state. This path never calls hurt/genericKill or the
  incoming-damage cancellation handler.
- `LivingEntity` tick at 471–472 invokes `tickDeath`; 556–561 removes the corpse
  with KILLED after 20 death ticks when the level ticks death. Normal `entity.load`
  does not insert a replacement or trigger EntityJoinLevelEvent. The mod's
  `GameAccess.restoreBody:873–883` does not reset health anyway. Its Post-tick
  dead-controller path at 962–976 accepts this same-object, same-world dead
  state with removal reason null or KILLED and calls `closeAfterDeath`.

No setter clamp or controller restoration obstacle was found in these source
paths. Actual terminal serialization/removal remains unverified until the live
fixture. This is not evidence of normal `die(DamageSource)`, damage callbacks,
combat drops, or accepted incoming damage.

Smallest normal setup, for the coordinator only during an authorized run:

1. In the isolated test world, use the normal body/thread creation path:
   `python3 tools/agents.py --game-dir run spawn --provider codex --model gpt-6.1-sol --reasoning-level low --body minecraft:cow --name SelfDeathDisposable --mode survival`.
   Record its new agent ID/body UUID. No inventory transfer, equipment, menu or
   item fixture is required. Never use the persistent ScriptProbe body/items.
   Spawn without an initial task creates a saved body/draft; `agents.py send`
   starts its normal bound thread. See `agents.py:124–144,164–172`,
   `bb-plugin/agents.ts:177–205` and `AgentService.createBody/bind`.
2. Send only that new agent a task using `agents.py send <new-agent-id> <task>
   --model gpt-6.1-sol --reasoning-level low`. The task is: read the prepared
   file, substitute its expectedBodyUuid literal with the recorded UUID, run
   its exact body once through minecraft_run with timeoutMs 60000, retain the
   complete raw tool response even when it isError, and stop without another
   game request/retry. The runtime error is expected evidence, not a failed
   attempt to repair. Verify returned binding/thread and body identity first.
3. Readiness is **not** a fixed sleep, provider activity, or a console log assumed
   to stream live. The host retains console logs until the tool report. Capture
   the initial action ID/status, then poll the existing read-only agent state
   (`agents.py get <new-agent-id>`) for a NEW action with type look and status
   completed from this invocation. `AgentService` exposes cached native action
   state; `AgentActions.start` creates a unique ID. Only this script may act on
   the disposable body. The forced look preserves its current orientation and
   occurs after listeners are registered. If no new completion appears within
   15 seconds, or the script already ended, do not apply the NBT mutation. No separate readiness
   mutation/helper is needed; the pending look wait or following waitForTicks
   remains outstanding when the native NBT-induced death is delivered.
4. After the readiness gate, the coordinator's existing authorized native
   command path issues exactly
   `data merge entity <recorded-body-UUID> {Health:0.0f}` once. Preserve the
   command outcome and exact target. Do not send the command through the waiting
   disposable thread or modify Invulnerable/NeoForgeData/UUID/other fields.
   Never use /kill, pause/unload as a death surrogate, or retry an unknown
   command outcome. This note prepares that operation; no live call is made.
5. Expected report: error contains body_dead, not a successful result or fixture
   timeout. `execution.logs` contains an armed row and a terminal health row
   immediately followed (among this script's logged rows) by exactly one death
   row with argc0/validtrue, stable Entity/UUID, bot.health/entity.health <=0,
   bot.isAlive/entity.alive false and empty inventory/cursor/closed menu. A
   waiting row may be absent if death arrives before the look await resumes.
   Preserve the full report/recentActions/requests/updates/bodyRelease; require
   confirmed scoped release or report its uncertainty. The script never sends
   a post-death mutation or catches/replays a rejected operation.
6. Independently retain command outcome and native exact-UUID health/death or
   removal observations (a corpse may disappear before a later Health read),
   then agent bodyLoaded/bodyLost and pendingWorldTools
   after corpse removal. Missing body alone does not establish death; pair it
   with terminal callback evidence and the recorded Health-only NBT operation. Verify no new
   native action after the marker and no remaining active script tool. Use the
   normal remove/archive workflow for this disposable record only after saving
   evidence, subject to coordinator authorization. Never respawn/substitute a
   player or clear another body's inventory. The empty fixture deliberately
   avoids claiming nonempty-inventory death cleanup coverage.

Source ordering checked: state.mjs:43–45,61–74 hydrates then queues zero-argument
health/death; bot.mjs:348,500–514 hydrates Entity/full state before dispatch;
AgentActions.closeAfterDeath/close:411–440 revokes lease, performs real cleanup,
then captures terminal state before stream failure; ScriptStream.write drains
queued frames before error. scripts.ts:51–64,240–246 defers only the approved
known rejection codes while streaming and awaits runner frame acknowledgements;
runner.mjs:44–55,74–94,117–121 preserves callback logs on failure, exposed by
scripts.ts:262–271. This preparation covers one terminal wait; interrupted
mining/route mutation progress is not claimed by it. Native execution is UNRUN.


## Packaged selected result: b21ce69

Selected terminal delivery PASS: UUID-only substitution in exact observe-self-death.js, new empty normal bound cow a94da934-0059-4270-9178-b54f9da459b3/agent60c380cd-a780-4539-a887-e519757c162c/thread thr_t3ikwpnxwu. One Health-only native merge after NEW completed look. Retained health0 sequence3 then exactlyone valid zero-argument death sequence4 with stable/hydrated Entity before expected body_dead. Native same UUID Health0/DeathTime4/Invulnerable1/binding retained, followed by absence.1completed request,8updates,5bridge operations,1048ms; no later scriptmutation/replay. Scoped release acknowledgement unconfirmed; later bodyLoadedfalse/bodyLosttrue/pendingWorldTools0. Supported archive attempted once and rejected body_missing_or_unloaded: dead record/thread retained without bypass. This proves NBT-induced terminal delivery only; combat/damage/drop/interrupted-action matrices remain untested. ScriptProbe health20/possessions untouched.

Executor Codex gpt-6.1-sol low; artifact SHA256
`e502233e1840adffaf3d6529749689c283e695264695e193a934edd70972c8ce`.
Exact source hashes, absolute call timestamps, raw/native evidence and limits:
`/Users/scott/.bb/thread-storage/thr_xykqkgui57/b21ce69-summary.json`.
