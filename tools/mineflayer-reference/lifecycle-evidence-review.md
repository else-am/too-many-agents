# Lifecycle evidence reconciliation

These are retained native reports, not new executions. Their tested revisions were not recorded; they must not be presented as current packaged validation. Exact executed scripts and report hashes are indexed in coverage.json with no API members promoted by these historical entries.

| Report in ignored `run/mineflayer-reference/` | Established result | Limit |
| --- | --- | --- |
| `departure-tick-wait-native.json` | World departure interrupted a 400-tick wait with `world_closed`; subsequent dig never started and independent reopened observation retained the stone target. | Release acknowledgement unconfirmed; no active mining or new-world mutation case. |
| `stream-departure-native.json` | Streamed execution stopped with `script_no_longer_controls_body`; subsequent dig never started and reopened target remained stone. | Host handling of known body/lease errors changed later; not proof of that later race fix. |
| `stream-pause-native.json` | Script completed across native pause/resume UI operations, counted 402 physics events, retained Entity identity and confirmed release. | UI responses capture transition states; no precise freeze-duration claim. |

The separate `cancel-native.json` and `cancel-mining-native.json` record stopped movement/mining and unchanged targets/inventory, but do not embed the executed script. Recover their invocation provenance before using them as script-contract evidence. `cancel-tick-wait-native.json` contains a pending invocation and final native observations, but no terminal tool result; it alone does not prove the guest cancellation result.

Remaining reconciliation: current native world-session/stale-handle safety, cancellation provenance, and distinct lost-reply behavior. The recorded real runner harness covers controlled cancellation and feed shutdown; it does not substitute for native world-session checks. Existing self-death host race probes distinguish known terminal rejections from unknown outcomes, but do not establish every transport failure path.
