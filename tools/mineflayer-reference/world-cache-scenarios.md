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
must use an explicitly assigned rebuilt artifact.

Public World reads now use that World's column cache. An explicit unload must
leave getBlock and numeric getters unknown even inside the last local snapshot;
bot.blockAt retains its separate native partial-observation fallback. This is a
guest cache correction, not a native terrain unload. The source audit found the
previous lookup routing through bot.blockAt could resurrect an unloaded cell.

Native-frame/storage integration contract (before this follow-up):
- A guest edits a remote column, queues its in-memory save, and receives a native
  unload frame before saving. The callback must receive that actual column,
  never undefined or a replacement at the same coordinates.
- Native block/light/biome/block-entity changes queue the changed observed column
  when a guest provider is installed; no default provider or host persistence.
- A provider that awaits while a newer edit queues the same coordinates must
  not erase that newer save. waitSaving drains both confirmed versions. Explicit
  provider failure retains pending work and rejects; there is no automatic retry.
- Native column events remain deferred until full hydration. A dimension change
  with unsaved guest columns rejects rather than misrouting old-dimension data
  to new coordinates. Cross-session guest persistence is not promised.


## Packaged selected result: b21ce69

World unload PASS: exact observe-world-unload.js once, callback unknown and all cache getters null, separate bot.blockAt retained, same column restored; zero native requests/updates,3bridge operations,609ms.

Executor Codex gpt-6.1-sol low; artifact SHA256
`e502233e1840adffaf3d6529749689c283e695264695e193a934edd70972c8ce`.
Exact source hashes, absolute call timestamps, raw/native evidence and limits:
`/Users/scott/.bb/thread-storage/thr_xykqkgui57/b21ce69-summary.json`.
