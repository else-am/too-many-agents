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
