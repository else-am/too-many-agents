# Self-death terminal delivery

Preauthored before implementation. Native kill checks remain UNRUN while the tester is unavailable.

- While a script owns an existing living body, register health and death listeners, then wait. Use a separate disposable bound fixture body and cause an actual accepted native death; never kill the saved ScriptProbe body or its inventory. The last authoritative frame must hydrate health <= 0 and alive/isAlive false before health() then exactly one zero-argument death(). No respawn or synthetic health. Event logging must reach the caller before the stream's terminal body_dead error.
- Include a pending action: its interrupted terminal sequence and verified progress must be in the terminal frame. A listener attempting another mutation must be rejected because the lease/body is dead; notification never restores control. Unknown action outcomes must retain fail-closed behavior.
- Duplicate unchanged dead state must not emit another death. A first already-dead observation must not manufacture a live-to-dead edge. Health changes while alive must not emit death.
- Generic unload, world departure, dimension replacement and lease cancellation must not emit death. A canceled death attempt with a living body must not emit death.
- If terminal snapshot serialization fails, retain an error and close; do not invent a health value, partial frame or successful death callback. A full/closed stream cannot promise delivery.

Focused non-native check: use the actual runner/QuickJS worker with the actual state adapter, send an authoritative dead frame, await the runner's frame acknowledgement, then terminate the stream with body_dead. Assert hydrated health/death log ordering and zero arguments survive the terminal failure. This verifies the guest acknowledgement boundary, not a live Mob kill or native snapshot construction. No changes to host/runner are planned unless inspection identifies an ordering defect.

## Delivery contract and focused evidence

The terminal path captures only the original body in the current server/world/dimension/session, with actual dead health and no generic discard/unload/transfer removal reason. Mutation authority is revoked first. Normal action/hand cleanup precedes the snapshot; columns and event queues survive until capture. The narrow AgentHands observation overload requires an already-closed proxy and dead same-level body; ordinary syncBody/action guards remain unchanged. Snapshot/cleanup errors close the stream without synthesizing data.

Focused actual runner/QuickJS check passed with the actual state adapter: alive health change, health0/death in order with zero arguments and hydrated values, no repeat on a second dead frame, then terminal body_dead failure retaining logs. Java21 standalone compilation passed. Native snapshot construction/cleanup and kill delivery remain UNRUN.

Integration caveat identified from source: heartbeat or a newly submitted awaitAction can receive a dead-body lookup failure on an independent HTTP request before the ordered stream's terminal frame is acknowledged; the host currently aborts immediately on those failures. Stream ordering alone does not resolve that concurrency race. Root owns any agreed host follow-up; unknown/lost/malformed outcomes must still abort without retry.
