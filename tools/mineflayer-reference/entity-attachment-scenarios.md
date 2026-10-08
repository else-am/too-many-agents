# Native vehicle attachment events

Contract recorded before implementation:

- A newly observed mount produces `entityAttach(entity, vehicle)` after both
  entities, passenger lists and the body inventory are hydrated. The body also
  retains its existing `mount` event. Event arguments are shared Entity objects.
- Dismount produces `entityDetach(entity, previousVehicle)` with the entity's
  current vehicle already null. Switching mounts reports detach then attach.
- Equal snapshots do not replay events. A vehicle temporarily outside the
  observed cache does not imply a native dismount. Once a previously unresolved
  relationship becomes observable, its first typed attachment may be reported.
- Initial hydration establishes a baseline silently. Removing an entity from
  the cache reports `entityGone`, not an invented native dismount. No placeholder
  vehicle objects are created.

Pinned Mineflayer 4.39.0 `docs/api.md` documents these events as vehicle
attachments. Its `entities.js` emits them from the legacy `attach_entity`
handler, whereas modern mounts use passenger updates. On Minecraft 1.21.1 the
native entity-link packet represents leashes. This adapter follows the
documented vehicle meaning using actual native passenger relationships; it
does not overwrite vehicles with leash holders or claim packet-event parity.

Native verification is pending. Extend the next necessary mount/dismount check
with typed arguments, hydrated callbacks and unchanged-frame counts; do not
repeat already passing vehicle trajectories solely for this event addition.
Cache-boundary behavior and switching mounts remain separate unrun cases.
