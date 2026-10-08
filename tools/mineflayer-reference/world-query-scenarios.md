# Read-only world query contract and scenarios

`installWorldQueries(bot, { getLoadedBounds } = {})` installs synchronous
findBlocks/findBlock/canSeeBlock/blockAtCursor/entityAtCursor, plus the pinned
blockAtEntityCursor and legacy blockInSight. It imports only bundled vec3 and
existing world-view.mjs. No registry additions, client, chunk loader, or native
operations are required.

Provide current `bot.blockAt(Vec3, extraInfos)` -> Block|null,
`bot.world.getBlock(Vec3)` -> Block|null, `bot.entity`, and `bot.entities`.
Entity geometry uses actual position/eyeHeight/width/height and Mineflayer-radian
yaw/pitch. Lead hydration's `(180-nativeYaw)*PI/180`, `-nativePitch*PI/180` matches
this convention. No guessed player dimensions. Return the original observed
Entity on selection. Blocks and positions keep their existing classes.

The optional synchronous hook returns `{min: Vec3, max: Vec3}` with integer
inclusive minimum/exclusive maximum covering every cached cell, or null when no
cells are observed. It clips searches only; holes still return null from lookup.
Without it, search checks the requested region. A search miss does not establish
absence in the unseen world. Rays throw `World query entered an unknown block
cell` when an unknown cell occurs before the result. Entity queries need only
check visibility as far as the nearest intersected candidate.

Extra-info booleans and the two-stage callback delegate to blockAt's real data;
the module does not manufacture sign, painting, block-entity, biome or light
observations. `useExtraInfo:function` supplies full-info Blocks to matching and
then the extra predicate, as pinned source does. `findBlock` fetches its final
Block with blockAt's default extra information. Predicate exceptions propagate.

Limits are explicit RangeErrors: at most 262144 candidate cells in the clipped
search box, 65536 requested results, 65536 entity candidates, ray distance0..4096,
finite coordinates/ranges and integral nonnegative counts. Search coordinates
must be safe integers after rounding/clipping. Existing world-view traversal and
shape budgets still apply. Zero search distance/count retain upstream's falsy
16/1 defaults; negative/fractional/nonfinite inputs do not inherit unsafe source
behavior. Callbacks also remain subject to the guest's execution budget.

## Source comparison boundaries

Sources: pinned Mineflayer4.39.0 `lib/plugins/blocks.js:118–239` and
`lib/plugins/ray_trace.js`; docs/api.md world-query headings; prismarine-world3.7.0
OctahedronIterator ordering. Executable source/library fixtures in
world-queries.mjs were authored and run with `--reference-only` before production
implementation. They invoke the actual installed plugins on deterministic block
lookups/section availability, not an extracted reimplementation of their methods.
No protocol client, server, or native world was started.

91 ordinary differential scenarios cover sorted coordinates and equal-distance
ordering across sections, floored origins, scalar/array/function matching,
count/radius defaults and boundaries, boolean/function extra info and exceptions,
findBlock class identity/null, negative coordinates/unknown search cells,
full/partial/fluid shapes, matcher iterator behavior, typed ray hits, visibility,
entity identity/occlusion and object filtering. Six independently specified
correction fixtures demonstrate upstream disagreements; nine invalid/bounded
cases execute only in the guest. Actual bundled QuickJS passes at 64MiB memory
and 512KiB stack; bundle has no external imports. All are library evidence only.

Deliberate source corrections:

- Search all intersecting loaded sections: source can stop before closer adjacent
  sections and its octahedron radius can miss diagonal cells inside the sphere.
  Stable ties retain the original section first-visit order then X/Y/Z scan order.
- No palette-prototype matcher probes: source can invoke a predicate on Blocks
  without position and extra data. Here predicates see only observed positioned
  Blocks inside the requested sphere, never null. Callback counts outside the
  query and palette side effects are not preserved.
- Zero yaw/pitch are valid; cursor origin uses observed eyeHeight instead of
  height. Entity distance and occlusion use eye-to-AABB intersections, not feet
  distance or center prefiltering. Faces/extents at maximum range are included.
- canSeeBlock aims at the center with center distance and returns a boolean for
  known observations; unknown observations fail explicitly. World-view's existing
  forward/range-limited shape corrections also apply.

Public entityAtCursor still excludes `type === 'object'`, like pinned source.
Selection is observation/collision geometry, not permission or interaction reach.
Native hydration of outline-only/noncolliding selections remains a separate
geometry contract; this module uses the supplied Block collision shapes.

## Lead-owned live checks (unrun)

Use the existing guarded native fixtures without adding a reference server:
verify known blocks near the cache boundary and across a cache update; an unknown
hole must fail cursor/visibility while a search returns only known matches.
Compare closest known block positions, sign extra data, crouched/nonplayer eye
origins, zero-angle view, slab/stair occlusion, and nearer/farther native entities
against independent native observations. Confirm entity width/eyeHeight scalars
and cache bounds update before synchronous queries run. Retain actual blockAt
nulls; never fill the bounded cache's unseen cells with air. Reference-server
conformance and native action/reach behavior remain unproven by these probes.

## Shared reference subset — preauthored

`world-queries-common.js` retains the existing native observer's nearest-table,
extra-info sign and table/occluded-wall checks. It deliberately omits opening a
window/cursor and the port-only unknown-cache exception. Native evidence for
those omitted checks is not relabeled as reference parity. The new reference
harness places the same named cells in its isolated world and uses an ordinary
player at10.5/-60/3.5; actual body geometry may differ from the historical native
run. Independent server block assertions verify the three block types after the
read-only procedure. Sign text is client-decoded; no separate NBT check ran. This checks known observations, not arbitrary unloaded/world bounds.

Actual reference run passed once on 2026-10-08 at 09:22 UTC. The server stopped
and its port closed. Startup retained an ArmorTrimMaterial decoding warning and
setup teleport movement warnings; this is not general protocol conformance. The
historical native report has no embedded executed source, so this new shared
source is not claimed as an exact paired native/reference execution.
