# Complete observed columns (contract before implementation)

Replace the false equivalence between the small local block cache and a loaded
chunk. Native bodies receive only already-loaded full columns within two chunks
of their current chunk. Reads never load/generate terrain and do not cross the
world border. Complete native section wire, biome palette, light values and
client-visible block entity update tags are copied on the server thread.
No private chest contents are added to the block-entity view.

Columns refresh at most five ticks apart, immediately on center or completed
operation changes. Local 33x17x33 observations remain per-frame and patch the
retained columns before events/action promises. Stable world/column references,
load/unload events, getColumn/At/getColumns and waitForChunksToLoad use genuinely
complete received columns. Wait captures the current 5x5 target, times out after
10 seconds, and does not silently force-load absent columns. Native mutation
checks continue to use authoritative world state, never guest column edits.

Preauthored native checks, all unrun:
- Compare loaded columns' full-height negative-Y/nontrivial palette blocks,
  biome IDs, bright sky/dark cave/emissive light, and sign update tags with native
  state at both near and remote cells; unloaded/outside-border remains unknown.
- Validate a 5x5 wait, an absent-column timeout, crossing chunk boundaries and
  load/unload callbacks after state hydration. Never call a partial cache loaded.
- Dig/place near the body and query bot.blockAt and retained getColumn Block
  before the action resolves; changes must agree. Observe distant command edit
  after completion and ordinary distant changes after the documented refresh.
- No private chest inventory leaks; no accidental world mutations from guest
  ChunkColumn setters. No world access off server thread, stale script caches
  after release, or reuse across dimensions/sessions.
- Initial 25-column QuickJS hydration stays within 64 MiB and bounded transport;
  changed-column deltas avoid repeating static terrain. Measure server and guest
  frame cost before calling the cache performant.

Full async storage, client-cache mutation semantics and wider loaded-region
coverage remain applicability/implementation work. This slice establishes real
columns, not arbitrary client/plugin/world persistence compatibility.

Route integration: a path extending outside the old local cache must submit a
bounded state box covering its selected edges and edit targets. Split at native
128-node/128-edit/65,536-cell limits; never submit unbounded terrain or silently
navigate without policy validation. Check near and remote segments plus a cell
that changes between planning and execution. Unknown outcome is not replayable.
