# Local Goal object contracts

Prepared before this differential execution. These objects are local predicate
and heuristic calculations, not native movement or entity observations.
`bot.mjs` imports pinned goals and changes only GoalBreakBlock; this check excludes
that wrapper, world-raycast goals and GoalFollow's entity-hydration contract.

Compare reference Node constructors with the plugin's pinned goal module bundled
for browser and executed in QuickJS (64MiB memory,512KiB stack). Cover Goal,
GoalBlock, GoalNear, GoalXZ, GoalNearXZ, GoalY, GoalGetToBlock, GoalCompositeAny,
GoalCompositeAll and GoalInvert. Record dependency/module/bundle hashes.

Coordinate cases must include negative fractional construction, public stored
fields, exact goal, nearby/far points, Y-insensitive XZ goals, range boundary,
adjacent-block geometry and inherited stable validity/change predicates. Exercise
public coordinate mutation. Independently assert representative geometric
outcomes as well as cross-runtime equivalence.

Composite cases must include empty/nonempty goal lists, original list/child
identity, push return/mutation, any/all/invert predicates and heuristics, and
delegation of changed/valid callbacks. Preserve the pinned empty composite
heuristic sentinel values; they are local library behavior, not route success.

Only explicitly exercised local members can use pinned-library evidence.
Pathfinder goal consumption, custom callbacks during action execution, native
movement, dynamic targets, raycasting, cancellation and arrival are not proved
by this check. No gameplay coverage promotion follows from class-name presence.
