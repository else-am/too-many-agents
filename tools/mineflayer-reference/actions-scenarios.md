# Core action scenarios (preauthored)

Source oracle: installed Mineflayer 4.39.0 `digging.js`, `generic_place.js`,
`place_block.js`, `inventory.js` activation functions, and `physics.js` look/lookAt;
docs/api.md corresponding public methods. Native validation is lead-owned.

- Dig lifecycle: held tool/helmet effects change Block.digTime estimate; loaded,
  diggable/reach predicate uses body eyes/native range. Auto, explicit face,
  raycast, and forceLook='ignore' preserve their requested intent. Native
  completion must precede verified block change and diggingCompleted(newBlock).
  Aborted/failed dig clears target fields, timestamps once, and emits aborted.
- Cancellation ordering: stop before start reply; replacement dig while start or
  cancel is pending; repeated stop; completed-event reentrancy. Cancel only that
  dig's ID, drain cancellation and terminal state before replacement/settlement.
  Known prestart rejection can be caught. Unknown start/await/control outcome
  forbids any subsequent module mutation; never replay.
- Placement: six faces, top/bottom half cursor, explicit delta, offhand, forced
  and ignored look. Verify actual destination state change before blockPlaced;
  same-type slab merging may change state without type. Native refused/no-op,
  unloaded target, empty hand and stale reference reject without invented blocks.
  _genericPlace returns reference position; placeBlock resolves void.
- Activation: block-local cursor and face are preserved; entity UUID identifies
  target. activateEntityAt converts the caller's world point into entity-relative
  hit coordinates without mutating the caller's Vec3. Reach/occlusion remain native.
- Look: cardinal/up/down yaw/pitch against pinned lookAt formula using actual eye
  height; force boolean propagated. Promise resolves after native facing state,
  including unchanged-angle no-op, rather than waiting for an absent event.
- Native combined check only: stop a partial dig and replace it, explicit-face
  dig, one real placement and one interaction/look. Check independent world,
  actual tool/item effects and terminal ordered state. No broad conformance claim.

Intentional corrections: no optimistic air insertion or local mining timer;
native auto mining selects a reachable face instead of forcing upstream's top;
canDigBlock uses native body eye/range, not fixed player 1.65/5.1; look does not
apply the player's sensitivity quantization; placement checks state changes,
including same-type slab merges; entityAt never mutates its supplied vector.
Combat, fishing, mounts, creative and general control-state APIs remain pending.
