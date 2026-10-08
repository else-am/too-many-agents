# World cache methods — contract before implementation

Use pinned prismarine-world 3.7.0's actual World and WorldSync implementation
for the guest cache, rather than another hand-written facade. It has no native
world authority. Keep the existing corrected, bounded ray geometry. Storage
providers and generators, when explicitly supplied by guest code, are ordinary
guest callbacks; no filesystem, host loader or server chunk generation is added.

Focused scenarios before the port:
- In the actual production runner, world.async.sync === world; synchronous
  column getters and async loaded getters retain the same column object.
- Mutate an observed column through sync and async setters. Observe typed
  old/new Blocks and coordinate events on the appropriate World emitter, and
  updated queries; no host request is sent. Next authoritative local frame
  restores the real local value. Both changed-block callbacks read fresh state.
- Set/unload a guest column through public methods; loading events observe the
  installed column. Missing synchronous reads remain unknown. Async missing
  column reads may reject, matching the pinned implementation.
- An explicit in-memory storage provider receives queued column coordinates and
  the actual column object; saveNow/waitSaving drain it, and unload waits for a
  queued save. No claim of native persistence or disk access.
- initialize applies its callback only inside the requested subregion; ordinary
  dimensions and return values match pinned World/WorldSync. Execution remains
  constrained by the existing guest CPU/memory/deadline limits.
- Async raycasts keep their source matcher-as-filter semantics but use the same
  corrected fractional-origin, forward/range-limited intersections as sync.
- Native column delivery still updates complete state before load/block events;
  loading, physical actions, permissions and native persistence remain the
  separate column/native scenarios, not established by this cache-only check.

Do not rerun earlier gameplay suites for this guest cache port. Native checks
remain blocked by the other connected game and must use a rebuilt artifact.
