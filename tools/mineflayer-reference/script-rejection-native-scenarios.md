# Typed script request rejection boundary

Authored before implementation on 9d8adc9. Preparation only; no native execution/build here. Root owns the actual host/QuickJS transport probe and any later native fixture.

- Deliberate pre-preparation validation (e.g. missing mine position, unknown action type, busy body): HTTP400 with `error.code=action_rejected_before_start` and the original diagnostic message. No new action, sequence or route preparation. Guest may catch it; existing action ownership must remain unchanged.
- Actual body lookup failure (dead, removed or unloaded): HTTP400 `body_missing_or_unloaded`. Actual requireScript lease mismatch: HTTP400 `script_no_longer_controls_body`. With an outstanding stream, host waits for acknowledged queued terminal hydration/closure, bounded by existing deadline/cancellation; it does not catch-and-dispatch a replacement. A generic unload never fabricates death. Use a disposable bound fixture for any eventual death check, never ScriptProbe.
- Unexpected exception after action publication/sequence advancement (including native stopMotion), or in route preparation/cleanup before sequence advancement: generic HTTP500 `callback_failed`, never a catchable pre-start rejection. No replay/new mutation; scoped end owns cleanup. Malformed input/helper failures not explicitly proven safe remain unexpected rather than becoming safe by message or unchanged sequence.
- Interrupted callback wait: HTTP503 uncertain outcome and interrupt flag restored, even inside the callback-specific catch. Callback timeout remains HTTP504 uncertain. Session exception remains HTTP409 `world_session_changed`. Host aborts immediately for all these uncertainty cases.

Source boundary: TooManyAgents.agentRoute callback.get unwraps only CompletionException/ExecutionException; AgentActions.start prepares routes before publishing an action, then invokes native stopMotion after publication. Therefore exception provenance must be explicit. Do not blanket-wrap RuntimeException or infer safety solely from actionSequence. Shared physical validators keep their prior exception/message behavior; only deliberate script-start rejection sites receive the new type. Terminal failures returned as acknowledged action statuses still use their existing ordered state barrier.

Implementation evidence: narrow Java21 compilation passed for ScriptRequestRejection, AgentActions, GameAccess and TooManyAgents against existing generated/transformed artifacts; no full build or execution. Direct deliberate validation throws in the script-start section are typed. Reused parsing/creative/settings/face/entity helper exceptions remain untyped; this conservative slice does not claim every invalid input is catchable. In particular, vehicle eligibility invokes hands.syncBody (item-use/equipment reconciliation), so it now follows the safe-validation boundary and is never classified by an unchanged sequence. Physical-start direct validations retain ordinary IllegalStateException/messages. The callback envelope is `{ok:false,error:{code,message}}`; the existing timeout envelope remains HTTP504 with its uncertainty message, and interruption explicitly returns503 with `interrupted_outcome_unknown`.

## Route origin validation before preparation

Preauthored on4b03b38: a requested route origin more than .2 blocks from the actual
body must reject before constructing/preparing/stopping a ScriptNavigation.
The typed HTTP400 before-start response retains both requested and actual
positions. Parsing malformed coordinates remains an ordinary error; this is not
a blanket classification of route preparation failures. The existing executor
origin check remains as a second guard. No accepted route, action sequence,
control cleanup, travel or edit may result from this validation rejection.

Affected native check: retain an actual drift rejection and its native ledger;
verify that no route was accepted and that the guest receives the typed known
error with both positions. Unknown transport, preparation and post-acceptance
errors still abort. No automatic replan/retry is introduced by this change.
The existing Cod detour/down scenario remains incomplete until a separately
reviewed solution and native evidence establish the multi-route behavior.
