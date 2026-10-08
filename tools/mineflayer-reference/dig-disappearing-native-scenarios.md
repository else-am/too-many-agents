# Script dig target disappearance — preauthored native criteria

The live reference at566d7ed observes externally removed air during an active dig
as one void completion/new-air event. The body must not claim it caused the break.
Use a NEW disposable empty survival body and one reachable stone, stable loaded
world/session and no nearby drops. Supply fixture coordination outside the guest:
observe a NEW actual native mine action in its mining phase before removing that
exact stone once. Do not trigger on time, console logs or an earlier look alone.

Required outcome: authoritative completed action detail target_removed; accrued
progress stays actual, no synthetic progress1 or harvest claim. Stream hydration
precedes void dig resolution and one new-air diggingCompleted event with cleared
target/face. No diggingAborted, item gain, tool wear from finishing, or replay
across160ticks. Independently inspect action ledger, world air, inventory and
release. Existing START effects are retained rather than rolled back.

Separate first-failure criteria: changed non-air target remains failed and intact;
air before a dig starts remains rejection; unloaded/out-of-range/denied access,
world departure, revoked ownership or ambiguous transport never become success.
Native abort cleanup must clear the established mining state; if cleanup throws
or changes air back into a solid, propagate failure, not success. Never replay.

The opt-in belongs only to direct script dig actions. Existing physical-tool mine
and selected-route edit behavior stay unchanged: route finishMining records its
own edits and must not attribute an external removal to the route. No generic
mapping of block_face_obstructed/mining_target_changed to success in the guest.
No new fixture/runtime operation has run; display setup remains blocked.
