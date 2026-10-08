# Lifecycle evidence reconciliation

These are retained native reports, not new executions. Their tested revisions were not recorded; they must not be presented as current packaged validation. Exact executed scripts and report hashes are indexed in coverage.json with no API members promoted by these historical entries.

| Report in ignored `run/mineflayer-reference/` | Established result | Limit |
| --- | --- | --- |
| `departure-tick-wait-native.json` | World departure interrupted a 400-tick wait with `world_closed`; subsequent dig never started and independent reopened observation retained the stone target. | Release acknowledgement unconfirmed; no active mining or new-world mutation case. |
| `stream-departure-native.json` | Streamed execution stopped with `script_no_longer_controls_body`; subsequent dig never started and reopened target remained stone. | Host handling of known body/lease errors changed later; not proof of that later race fix. |
| `stream-pause-native.json` | Script completed across native pause/resume UI operations, counted 402 physics events, retained Entity identity and confirmed release. | UI responses capture transition states; no precise freeze-duration claim. |

The separate `cancel-native.json` and `cancel-mining-native.json` record stopped movement/mining and unchanged targets/inventory, but do not embed the executed script. Recover their invocation provenance before using them as script-contract evidence. `cancel-tick-wait-native.json` contains a pending invocation and final native observations, but no terminal tool result; it alone does not prove the guest cancellation result.

Remaining reconciliation: current native world-session/stale-handle safety, cancellation provenance, and distinct lost-reply behavior. The recorded real runner harness covers controlled cancellation and feed shutdown; it does not substitute for native world-session checks. Existing self-death host race probes distinguish known terminal rejections from unknown outcomes, but do not establish every transport failure path.

## Recovered selected-route cancellation

Two additional reports embed complete executed source and successful terminal tool results:

- `route-cancel-air-native.json`: `setGoal(null)` during a native gap jump produced `GoalChanged` and an interrupted native route. The body continued falling, landed, and did not resume the route. Release was confirmed. This file has no separate independent world observation.
- `route-cancel-dig-native.json`: cancellation on the first completed break retained exactly that break and one durability use. Three remaining blocks stayed intact over twenty ticks, the native route was interrupted, and release was confirmed. A separate native observation is included.

Both are hash-indexed without promoting API members. Revision provenance remains absent. They establish historical selected-route cancellation, distinct from the earlier unproven invocation provenance for direct movement/mining cancellation and from external whole-script cancellation.

## Known terminal errors versus a lost reply

The implementation-child report for `0612169` is retained as `terminal-host-race-recorded`. It ran the actual host HTTP decoder, script coordinator and worker/QuickJS state adapter with controlled fetch responses. Three known body/lease rejection cases delivered hydrated health/death callbacks before terminal failure. The lost-start-reply case aborted before the guest catch could dispatch a replacement, with one action request and one scoped release. This is host-boundary evidence, not an actual game/network fault injection.

The exact historical probe is preserved in `recorded/self-death-host-race-0612169.mjs`, including its original absolute worktree and result paths. Do not run that archival file blindly: those paths may now point at different source revisions. The indexed report records the original outcome; a future affected rerun must use explicit current inputs and a fresh output path. No rerun occurred during this reconciliation.

## HTTP action-start uncertainty correction

The current callback timeout response is HTTP504 with `error.message=callback_outcome_unknown_do_not_retry` and no error code. The host previously mapped every `minecraft_action_failed` response to safe pre-start rejection, including that timeout. The preauthored `action-start-http-uncertainty.mjs` reproduced guest `UNSAFE_CATCH` before the fix.

The host now retains HTTP status and only preserves the existing safe-rejection classification for structured HTTP400 `callback_failed`. Timeout, interruption and unstructured error responses abort the script. Three actual-host/QuickJS controlled-response cases passed, with one action request, no guest catch/replacement and one scoped release. No Minecraft process or real network fault was used. Remaining native structured400 acceptance-phase distinctions and the exact native terminal-code mapping are being reviewed separately; this fix does not claim those resolved.

The follow-up native source review established that `callback_failed` also covers exceptions after action acceptance, and flattens the two lifecycle codes expected by the terminal stream gate. The host whitelist therefore now requires the dedicated `action_rejected_before_start` code rather than `callback_failed`. A preauthored generic400 case reproduced the unsafe catch; the five-case host probe now passes both uncertain-abort and explicit-known-rejection recovery. Native typed emission is being implemented separately before packaging these changes together.

Native typed emission integrated at `572692c`: intentional pre-preparation validation uses a dedicated rejection type; route preparation and unexpected/post-acceptance exceptions remain uncertain HTTP500. Body/lease source sites now preserve their exact typed codes. Inner callback interruption restores the interrupt flag and returns HTTP503. The combined full build passed. The existing four-mode host race probe was rerun against current source with the actual new HTTP400 envelope and passed; this still uses controlled fetch and does not claim packaged native fault injection.


## Packaged 572692c selected validation

The native held-control route rejection passed through the typed before-start path; the script cleared sneak and completed a look without any native route sequence or movement. This corroborates the native producer plus host mapping, without a separate HTTP wire capture. The guarded world-switch check stopped with `local_player_unavailable` after its readiness look, before the queued sentinel look. Its release acknowledgement was unconfirmed; after returning to the original guarded world, independent observation found the same empty living disposable body idle with no pending tools. No lost-action reply was replayed. Exact executed sources and raw reports are indexed as `known-rejection-native-572692c` and `world-switch-native-572692c`; these do not establish every cancellation/transport combination. All new disposable bodies were supported-archived, original belongings restored, and exact JVM77764 saved/stopped.
