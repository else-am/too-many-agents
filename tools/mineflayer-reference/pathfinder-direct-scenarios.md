# Direct Pathfinder contracts (preauthored)

Pinned source: mineflayer-pathfinder 2.4.5 index.js postProcessPath /
monitorMovement, lib/physics.js canStraightLineBetween / canStraightLine.
Its simulations use PlayerState; those dynamics are not authority for native mobs.

- Query an open supported straight route from a supplied startPos different from
  bot.entity.position, with a supplied Movements subclass different from the
  installed movements. Shortcut results and execution selection must both use
  that query. A custom neighbor restriction or positive exclusion cannot be
  bypassed. Start on a slab while the observed body is airborne elsewhere.
- Straight, diagonal and fractional support geometry: normal, short/tall/wide
  bodies; narrow wall corner, ceiling, unloaded cell, liquid, hazard, excluded
  support and entity collision. Candidates are synchronous geometry/policy
  checks only; native bounded trajectory simulation decides executability.
- No shortcut crosses a dig/place/use edge. Preserve all edit records and the
  suffix after the first edit; partial AStar contexts remain resumable. Turning
  shortcutting on changes actual selected native edges, not just displayed paths.
- Follow a valid entity with allowFreeMotion: bounded direct movement toward its
  current position, stop within range, continue a dynamic goal when it moves.
  Invalidate/move/replace target while start/cancel replies are pending. Existing
  cancellation, stop, terminal state/event reentrancy and no-unknown-replay rules
  apply. Obstructed/free-motion-ineligible targets use ordinary AStar.
- Native direct preflight: check supplied loaded cache/revisions, species,
  geometry, body box and surveyed corridor; simulate controls against real
  collisions before first movement. Selected walking/step/jump uses native travel
  exactly once per tick; no second path search, teleport or Navigation fallback.
  Distinguish a known preflight rejection (safe ordinary-route fallback) from a
  changed trajectory after movement (terminal failure, no hidden replay).
- Graceful stop completes current bounded direct segment/landing. Immediate
  cancel clears only owned controls. Native entity/world changes cannot carry
  the body through a policy-forbidden or unobserved corridor.

Focused source/QuickJS checks and Java compilation are not live conformance.
Native fluids, climbing and species/effect-specific direct dynamics that the
existing native predictor cannot model remain explicit pending work; ordinary
planning remains available rather than granting player abilities.

Implementation contract: raw integral Move records may carry
`direct:{from:{x,y,z},x,y,z,jump,minY,maxY}`. Direct nodes have no edits;
segments span at most eight horizontal blocks and one vertical block. The
native center must remain within .2 blocks of the surveyed segment and inside
its vertical envelope. `jump:false` forbids the native planner from adding a
jump. Existing route snapshots, action IDs and ordered terminal states apply.
`route_direct_preflight_rejected` is emitted only while predicting the first
edge on tick one, before route travel/edits; it disables direct attempts for
that target and permits fresh ordinary AStar. Later trajectory failures,
start drift and unknown outcomes do not use this fallback.

The optional synchronous `canShortcut(from,to,{movements,startPos})` hook can
further restrict candidates; no hook is now required. Public path positions
remain optimized while private execution records retain integral endpoints,
selected direct corridors and the complete first-edit suffix. Query fractional
support uses the supplied position's real collision shape, independent of the
actual body's current onGround state. This corrects the upstream query-origin
assumption. Free pursuit uses physical distance, preserves dynamic goals and
emits normal arrival for static goals; upstream's early-return free-motion
branch can otherwise prevent goto completion. A target moving more than .25
blocks cancels the scoped action and waits for its terminal observation.

Focused source/guest check results are in thread storage
`pathfinder-direct-probe.mjs` and `pathfinder-direct-results.json`: ten groups,
including real pinned Block/Movements/AStar, actual pinned shortcut endpoint
comparison, native-capability gap policy, retained edits, and browser-bundled
QuickJS at 64 MiB / 512 KiB stack. The reference player simulator retained five
nodes in the flat fixture while native candidate selection reduced them to
one: trajectory feasibility is deliberately delegated to Java, so this is
not player-physics parity. The Java 21 class compiles against existing generated
Minecraft/NeoForge and lead project artifacts. No game/build/install was run.

Pending live checks: straight/diagonal/slab shortcuts, jump pursuit, preflight
fallback, moving target cancellation, graceful stop, changed corridor and
native collision with a target approached inside a coarse entity-index cell.
Direct fluids/climbing, special species/effect dynamics, larger vertical
segments and chains requiring multiple jumps are not established; ordinary
selected-edge planning remains available. Conservative corridor checks may
retain valid ordinary edges instead of shortening them. These limits are
pending work, not a declaration of complete Pathfinder conformance.
