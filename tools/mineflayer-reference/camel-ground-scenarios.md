# Exact standing Camel ground routes — preauthored

Generated Camel.java222–240 delegates to inherited ordinary travel only when
refuseToMove is false. Its exact CamelMoveControl659–668 delegates to MoveControl
and can stand a sitting Camel up. This slice admits already-standing, nontransition
dry Camel only; it does not fake a pose/timer or claim sitting navigation support.

One future fresh healthy empty standing Camel uses actual native width, height,
stepHeight, speed and jump power on inspected loaded dry floor. Traverse a
body-sized obstacle detour and supported elevation/drop; verify native positions,
health/items, route completion and cleanup independently. Direct shortcuts remain
disabled under distinct native-camel-ground-post-tick capability. No runtime run.

Grazing/rearing immobility is independent of refuseToMove. Native NoAI ticking
can begin grazing: reject an already immobile Camel and terminate an active route
when that state appears, without clearing native eating/rearing flags. Future
native checks must distinguish that rejection from successful standing movement.

Revalidate refuseToMove, isImmobile and actual pose/age/geometry/controller/navigation before
owned movement. Pose and baby refresh may resize/reposition with setPos, so end
only captured ownership before native refresh and allow actual change unchanged.
No synthetic standUp, last-pose tick, dash timer, target, velocity or brain tick.
Existing ordinary native ticking retains dash cooldown/head clamp behavior.

Separate cancellation/pose/growth/state criteria require known original identity
and lease; cleanup cannot act on replacement state. Fluids, sitting transitions,
mounted control and dash navigation are pending distinct modes, not exclusions.
No extra state mutation to manufacture fixture eligibility. Preserve ScriptProbe.
