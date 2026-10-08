# Native forcedMove (preauthored)

- Accepted same-level command teleport changes body position/rotation. After the
  next full hydration forcedMove has zero arguments and reads the actual new
  position from the same shared bot.entity, with no fabricated velocity/height.
- Same-level changeDimension(DimensionTransition) returning the existing entity
  counts as an accepted teleport; null/replacement/cross-level outcomes do not.
- randomTeleport into collision or liquid tries a teleport then rolls back and
  returns false: no forcedMove. Accepted randomTeleport produces one callback,
  not one from both the outer attempt and inner teleport.
- Ordinary travel, moveTo/setPos, look and arbitrarily large native movement
  deltas alone do not count. An accepted no-op with unchanged position/rotation
  does not count. Dismount/relative reposition through teleportTo(DDD) does.
- A teleported mount relocates the controlled passenger via native positionRider
  and Entity::moveTo; observe that actual moved body once. An unrelated entity's
  teleport must not notify this body. Rejected mount randomTeleport suppresses
  its passenger trial/rollback as well.
- Two separate accepted operations before one snapshot queue two callbacks; each
  callback sees final fully hydrated state, not fabricated intermediate frames.
  Nested operations affecting the same observed body do not double-count.
- Same-level identity/lease captured at operation start must still match at
  acceptance. Release, replacement, cross-dimension removal/session departure or
  thrown native operation does not leak a callback into a newer script.
- Queue overflow/observer failures affect only that lease, never native movement.
  One focused compile/source-target check only; all live fixtures stay root-owned.

## Source-derived contract

Pinned Mineflayer4.39.0 `lib/plugins/physics.js:374-453` hydrates clientbound
position/rotation then emits forcedMove without arguments. `docs/api.md:1485`
also mentions spawning. No client packet, respawn or spawn is synthesized here.

Generated 1.21.1 `Entity.java:2742-2790`: same-level
`teleportTo(ServerLevel,DDD,Set,FF)` moves and returns true; `teleportTo(DDD)` moves
only on ServerLevel; both call teleportPassengers, which uses positionRider with
Entity::moveTo. dismountTo and teleportRelative delegate to the latter method.
`Entity.changeDimension:2573-2617` also observes accepted same-level transitions
returning the original entity; the actual nullable/replacement return is preserved.
Passenger changeDimension calls nested under that operation defer to its outcome.
Cross-level calls keep native replacement/removal and existing script aborts.
`LivingEntity.java:3282-3328` randomTeleport uses that same method for both a trial
and rollback, then returns acceptance. The wrapper observes its whole operation
and discards rejected attempts; native calls/return values/exceptions stay intact.
`LivingEntity.stopSleeping:3421` directly calls setPos and is not an observed
teleport. General moveTo/setPos are deliberately not position-delta heuristics.

The transient observer selects only exact active body identities, including
passengers of the operation root. It stores initial position/rotation and a
captured script-token predicate, with at most64 observers per operation. Nested
attempts for the same affected body defer to the outer acceptance decision;
finally clears scope even on native exceptions. On acceptance, changed bodies
still in their original level/lease enqueue `{name:'forcedMove',subject:id,
entities:[]}` in the existing 64-event/1MiB queue. Queue release/reset and
snapshot draining stay unchanged. The predicate is checked again at drain.
No nearby-body broadcast or strong per-entity history is introduced.

Root integration: translate only that event record to `bot.emit('forcedMove')`
after full frame hydration. Do not forward subject/cause as public arguments.
Two independent accepted moves in one frame remain two records, whose callbacks
both see current fully hydrated state. Equal position/rotation operations yield
no event; pinned equal-value correction packets are not invented for native Mobs.

Standalone compile of changed sources and exact generated method-target source
inspection passed. This does not validate packaged Mixin transformation or live
teleport/rollback delivery. Those checks remain lead-owned; no game/build/lifecycle
operation ran during this slice.
