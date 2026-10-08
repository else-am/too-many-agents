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


## Prepared single ordinary native attachment check — unrun

`observe-entity-attachment.js` uses one NEW empty healthy awake unmounted ordinary Cow bound body and one NEW exact ordinary tamed adult horse with real saddle, no passengers and no body armor requirement. Substitute only the two inspected exact UUIDs. Codex gpt-6.1-sol low is the future executor; preparation does not authorize spawn/fixture/launch. Preserve ScriptProbe, all original items/cells and other active work.

Before invocation, inspect original dry floor/clearance x358..365/z108..114/y-61..-55 plus the actual body/mount boxes and safe native dismount apron. Suggested fixture is stone floor y-61 with clear air through-55; body stable at360.5/-60/110.5 and horse approximately361.8/-60/110.5. These coordinates are suggestions, not proof of native reach: independently resolve exact horse UUID/Tame/SaddleItem/passengers, actual dimensions/health/attributes, current empty main hand/no sneak and actual native unobstructed interaction reach immediately before invocation. Keep all horse positions within reach without altering speed/attributes. NoAI is acceptable for this menu-free mount-only fixture, but retain whichever ordinary state was prepared; do not toggle it or counters during the check. No surrounding riders or auto-capture boat. If geometry/reach/source prerequisites differ, stop before dependent action rather than guessing unknown cells or inventing guest fields.

Listeners are installed before3 baseline ticks, one synchronous-void `mount(horse)` and its ordered positive tick wait. Each body entityAttach callback checks both arguments are shared Entity objects in bot.entities, same position Vec3 objects, body.vehicle/bot.vehicle already that horse, actualbody sole passenger and inventory/cursor/window already hydrated. Exactlyone attachment must remain across20 ordinary mounted ticks. One synchronous-void `dismount()` then bounded40-tick observation plus10 unchanged ticks checks entityDetach with the same previous horse identity, current body.vehicle/bot.vehicle already null and horse passengers already empty. Counts must be1/1 in attach/detach order with no replay, unchanged health/inventory/selection, cleared controls and no menu/cursor. Failure preserves at most8 events/errors; no error-handler mutation or retry. No navigation, moveVehicle, manual movement, boost, jump or exact position/zero-velocity assertion is included.

Native adapter source: relationships are linked before event dispatch; inventory/menu/equipment are hydrated before entityEvents emit. mount maps to ordinary native interact and dismount to native dismount; both returnvoid. Public callback counters are distinct from native action evidence. Coordinator must retain exact interact/dismount ledger, actual body UUID in horse Passengers while mounted if the window is caught, final native body vehicle/passenger absence, saddle/inventory unchanged and release/ownership. If transient native mounted NBT is missed, report that limitation rather than inventing it or rerunning. Actual native callbacks/typed state remain the measured result.

This checks documented vehicle attachment semantics only. Preserve the recorded Mineflayer docs vs legacy attach_entity/modern passenger/native leash packet distinction above; no packet-exact parity claim. Initial mounted hydration, cache-boundary disappearance/reappearance, mount switching, leashes or extra species remain unrun. Use one exact-source bounded future call (e.g.90s), retain artifact/hash/model/effort/source/timestamps/raw/native evidence, and restore/archive only known-empty owned fixtures/bodies during a separately authorized lifecycle. Syntax parsing invokes no game code.
