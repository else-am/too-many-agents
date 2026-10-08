# PC 1.21.1 column scenarios

Authored before the adapter. Reference: prismarine-chunk 1.41.0 selected
`src/pc/1.18/ChunkColumn.js` and its common palette/bit-array helpers;
minecraft-data 3.117.0 and prismarine-block 1.23.0. Library-only checks, no world
or native serialization conformance claim.

Focused checks:
- Construct default and explicit-height columns; inherited/static public method
  surface, shared Block identity, section lookup, empty sections, out-of-height
  reads. Initialize a small column with null and nonempty cells.
- Mutate/read full cubes, fluid and slab states at section/corner/negative-Y
  boundaries. Exercise type/data, biome aliases, block/light/entity properties.
- Expand a section through single, indirect and direct palettes (more than 256
  different states); compare upstream bytes, values, section emptiness and
  palette visibility. Biomes likewise include more than eight IDs.
- Round-trip block/biome bytes, distinct sky/block light masks and arrays, typed
  block entities, JSON and disk section loading. Include a positive minY.
- Hydrate source-generated wire plus native-layout nibble arrays with asymmetric
  nibble positions. Preserve block-entity world coordinates in NBT while using
  local x/z keys. Empty versus absent light sections must stay distinguishable.
- Reject truncated/trailing/oversize native bytes, invalid dimensions, unknown
  state/biome IDs, invalid palettes/indices/counts, light lengths and block-entity
  coordinates. No partial publication of a failed hydration.
- Actual browser bundle in QuickJS: 64 MiB memory, 512 KiB stack, time bound;
  no external runtime imports or Node built-ins in its dependency graph.

Source discrepancies to check separately rather than call conformant:
- `ChunkColumn.fromJson` takes emptyBlockLightMask from emptySkyLightMask.
- `CommonChunkColumn.getBiomeData` indexes registry[biome] instead of biomes.
- `loadBlockEntities` shifts world x/z right four rather than masking local x/z.
- Disk `loadSection` and nibble helpers assume minY <= 0 using Math.abs.
- PC1.18 source has no loadLight method; loadParsedLight is its actual public
  light loader. loadBiomes is intentionally a no-op, dumpBiomes/getMask undefined.
  Generic multi-version declarations/docs are not additional PC1.21.1 methods.

Integration uses complete native columns, never synthesized observations. Native
numeric state/biome IDs must match the supplied registry. Unavailable light data
is represented by null, not silently asserted to be an observed zero field.

## Integration contract

`createChunkClass(registry, Block)` returns the full selected upstream synchronous
class, with `ChunkColumn.fromSnapshot(row)` as an integration extension. Required
registry fields: existing Block fields plus `blocksArray` and `biomesArray`.
Biomes must be contiguous in actual native ID order; metadata names/IDs must be
validated against native registry names by the caller. Block state IDs use the
pinned vanilla registry. No second Block class or version-data loader is bundled.

Row shape: `{x,z,minY,worldHeight,data,skyLight,blockLight,blockEntities}`. x/z are
world chunk coordinates consumed by WorldSync, not stored on the column. `data`
is canonical base64 for concatenated native LevelChunkSection.write bytes,
ascending section Y. Each light list has worldHeight/16+2 entries, starting at
minY/16-1. An entry is observed uniform integer 0..15 or canonical base64 of 2048
native DataLayer bytes. Native code must resolve absent light data; this snapshot
extension rejects null instead of silently converting unknown light to zero.
`blockEntities` entries are `{x:local0..15,y:absolute,z:local0..15,nbt:typedCompound}`.
Only native getUpdateTag data is expected. Existing transport must revive NBT
numeric representations before hydration as needed. No NBT field is invented.

Source APIs checked: generated Minecraft `LevelChunkSection.write` writes the
non-air short followed by block and biome PalettedContainers; `DataLayer.get`
reads low nibble first at `(y<<8)|(z<<4)|x`. Thus snapshot hydration deliberately
uses native little-endian nibble order, not upstream loadParsedLight's big-endian
word reader. The latter public library method remains available unchanged.

Build pins: prismarine-chunk@1.41.0, smart-buffer@4.2.0, buffer@6.0.3;
transitives base64-js@1.5.1, ieee754@1.2.1. Import only
`prismarine-chunk/src/pc/1.18/ChunkColumn.js` plus selected common helpers.
The executable scenario demonstrates esbuild `platform:'browser'`, alias
`buffer` to `require.resolve('buffer/')`, and a virtual inject module exporting
`{Buffer}` from that resolved file. Its onLoad result must set resolveDir.
This is lexical pure-JS Buffer injection, not access to Node globals or a loader.
Keep adjacent/transitive license notices in the packaged artifact.

Bounds: section-aligned minY within +/-1048576, positive section-aligned height
at most 4096, at most 4 MiB column bytes. Wire load validates palette IDs/indices,
word counts and complete consumption before publishing decoded sections.
fromSnapshot additionally checks complete light lists and block-entity positions.
Ordinary library setters edit local state only; they do not mutate native blocks.
The retained cache and native update freshness are root-owned.

## Focused evidence and limits

Run with MINEFLAYER_REFERENCE_ROOT and MINEFLAYER_PLUGIN_ROOT pointing to the
lead's read-only installed packages. `--reference-only` ran before adapter
implementation. The final run compared ordinary public operations, initialization,
shared Block identity, 300-state direct palette promotion, block/biome/light wire
round trips, JSON and disk section loading against upstream. Additional checks
separate corrected behavior: direct palette JSON data preservation, empty block
mask restoration, biome lookup, bulk NBT local coordinates, positive-minY disk
section placement and biome direct-palette width. Twelve malformed native rows
reject, including unknown state/biome IDs and invalid indirect palette indices.

The actual browser bundle ran in QuickJS with 64 MiB memory and 512 KiB stack. Twenty
five retained 384-high columns with compact uniform light values plus an asymmetric
native-format light layer hydrated in 46 ms; measured VM memory was 8,319,189 bytes.
This fixture has singleton block/biome palettes: it is a feasibility measurement,
not a bound for every world or for the combined production bot bundle. All input
bytes were derived from sources/library serialization, not emitted by Java or a
live world. Root owns native fixture validation and full-cache integration.

Further source corrections are explicit: the direct palette JSON constructor
ignores its data argument; biome palette promotion defaults to eight bits instead
of actual native registry width; disk-loaded block palette promotion defaults to
eight bits; singleton read discards its native non-air count. These are repaired
locally. Ordinary section mutation still follows upstream's `stateId!==0` count
semantics (cave_air/void_air differ from native non-air counting); native load
preserves the authoritative count. Generic docs mention a version property and
loadLight, but this selected PC1.21.1 class exposes neither; no fake methods or
all-version promises are added. Host/native conformance remains pending.

## Empty light masks (follow-up source correction)

The pinned fromJson swaps both empty masks; the existing adapter corrected only
emptyBlockLightMask. A column whose empty sky mask has bit1 and empty block mask
has bit2 must preserve those distinct masks through toJson/fromJson and dumpLight.
This affects guest column serialization, not native light computation. Check
against the serialized masks, since reproducing the upstream swap is incorrect.

Focused source/QuickJS check passed: upstream swaps distinct bits1/2; adapter
JSON restoration and dumpLight both preserve them. Evidence is in ignored
run/mineflayer-reference/light-mask-correction.json (exact probe included). This
is guest serialization evidence only; the initial probe's missing registry
indexes were corrected before the successful check. No gameplay suite reran.
