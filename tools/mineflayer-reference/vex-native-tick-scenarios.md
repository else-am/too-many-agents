# Vex native-tick flight — preauthored, unrun

Use a fresh exact healthy empty Vex with legitimate native life/target state and
an independently inspected loaded dry fixture. Preserve actual geometry, owner,
bound origin, limited-life timer, charging, gravity and motion. No attribute,
life, velocity or noPhysics repair to manufacture readiness.

Before implementation, resolve the native execution boundary:

- Vex.tick sets noPhysics around super.tick, then clears it. The existing Post
  travel occurs after that bracket and cannot stand in for native Vex movement.
- Permit exactly one reviewed native travel inside the real bracket, without
  enabling goals/brain/navigation or adding another aiStep/travel call.
- Preserve actual MoveControl input: near bounding-box-size distance switches to
  WAIT and halves velocity; farther input adds modifier*.05 normalized direction.
  Do not synthesize damping or replace target-dependent yaw with another law.
- Current route admission rejects noPhysics. A scoped Vex exception must apply
  only to its exact owned native phase, not arbitrary noclip entities.
- Entity.move's noclip early path bypasses collide and the current post-collide
  destination fence. Validate full requested destination, selected corridor,
  body/eye loaded/revision volume and body-box bounds before that early setPos.
  The existing HEAD loaded-sweep check alone does not establish these bounds.

One future bounded clear-air selected route should establish actual up/detour/
supported-endpoint behavior with exact one-travel accounting, bounded traces,
unchanged belongings and native ledger/ownership evidence. Resolve whether a
supported endpoint is attainable under native noclip before asserting onGround
landing. Do not manufacture contact bookkeeping or collision flags.

Native solid traversal requires an explicit planning/permission/callback model;
clear-air criteria do not silently exclude that native capability from scope.
Retain native inside-block callbacks and limited-life behavior, with no target or
life timer suppression. Fluid/solid/state transitions remain pending modeling.

Separate cancellation must end scoped input/travel permission without touching a
replacement body/controller/world/lease. Observe passive residual motion and the
outer cleanup policy honestly; no post-cancel route actions or hidden retry.
All implementation, phase hooks, convergence and cancellation remain UNRUN.
