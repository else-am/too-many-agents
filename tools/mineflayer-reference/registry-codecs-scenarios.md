# Dynamic registry codecs (contract before implementation)

PC1.21.1 uses segmented registry_data records. Preserve synchronous
loadDimensionCodec(packet) -> void and writeDimensionCodec() -> keyed packet
objects, with script-local effects only. No method sends packets or edits world
registries. Native initialization supplies actual dimension/biome/chat entries
in native ID order, including complete typed NBT; no static vanilla substitution.

Important cases:

- Compare all three loaded projections with actual prismarine-registry1.12.0:
  dimension names/minY/height; biome metadata/effects/custom entries; chat formats,
  parameters and shared index objects. Both public wire loading and native initialization use zero-based chat IDs:
  the actual pinned PC1.21.1 incrementedChatType feature is false (despite the
  upstream source comment mentioning 1.21).
- Unknown registry sections remain ignored by the public loader. Missing native
  sections, duplicate names, absent known-pack values and malformed shapes fail
  explicitly. Malformed final entries must not leave partially updated indexes.
- Export produces valid modern registry_data packets with array entries. Preserve
  the complete captured NBT, dimension height/flags, nested biome music/particles,
  chat narration and unknown properties. Independently serialize/parse outputs
  with the real pinned configuration packet codec.
- Demonstrate upstream write defects before implementation: dimension height is
  lost, biome entries are a compound rather than an array, nested effects can be
  wrapped as integer tags, and chat export uses static data after dynamic import.
  Correct these; do not claim byte-for-byte equality to invalid output.
- Changes to the published dimension minY/height/name and biome temperature,
  rainfall, legacy metadata and existing typed effects are reflected on export.
  Preserve all other captured tags. A new effect field without a known NBT type
  rejects and must instead be supplied as typed NBT through loadDimensionCodec.
- Export returns independent objects. Caller edits to supplied or returned packets
  cannot mutate retained source records. Native codecs are data, not authority to
  change columns, permissions or actual world registry IDs.
- Bounds: three native sections, <=4096 entries per section, bounded tree depth,
  node/string sizes and cumulative stored data. Oversized imports fail before
  mutation. Guest runtime memory/CPU budgets still apply.

Preimplementation reference-only run first; then one focused actual QuickJS
64MiB/512KiB comparison/correction run. Live native/custom-registry validation
and combined-bot startup remain separate from these source-generated fixtures.

## Implemented outcome

Public wire-loading projections match the pinned source for all three selected
sections, including a custom biome. Native initialization deliberately preserves
native fields over static same-name biome defaults: upstream overwrites an actual
has_precipitation value with the pinned boolean. Export normalizes that public
boolean to the captured byte type; native/custom records retain their actual data.

The actual browser-bundled QuickJS check at 64 MiB/512 KiB passed projection
comparisons, independent Minecraft registry_data encoding/decoding, complete
source-record roundtrips, changed dimension/biome/chat exports, returned-copy
isolation, atomic malformed import, unknown-section ignoring and entry bounds.
The default reference contains four dimensions, 64 biomes and seven chat types.
These are source-generated packet fixtures, not observed Java codec bytes or a
live custom-datapack test. No additional native run or broad suite was performed.

Captured packet exports preserve unknown fields and all chat decoration/narration
NBT. Direct edits to derived chat formatting indexes do not rewrite captured chat
NBT; loadDimensionCodec is the typed chat replacement path. Dimensions and biomes
can be renamed or edited with their captured type templates; adding entries needs
a complete typed import. Static login/protocol data remains the pinned reference.

Root initialization now requires the three native registryCodecs sections and
checks biome ID/name consistency with the item codec registry. Public registry
edits remain script-local; existing ChunkColumn factory mappings retain their
captured native IDs. No registry method edits native world data or permissions.
Native entry encoding has a 4 MiB binary limit; the guest adds 4096 entries per
section, 262144 tree nodes, depth64 and 4 Mi UTF-16 string units across stored
sections. These are distinct bounds. Native codec construction precedes native
projection bounds and is not an arbitrary-mod code sandbox.

Combined Java/plugin/package build passed on implementation 1bc61ab. The actual
production guest bundle then loaded at 64 MiB/512 KiB, reporting 8,188,783 bytes
of memory and 276 ms; this does not exercise createBot or native column hydration.
JAR SHA256: de454011506d206f32a56ca3d8b89cb6159bdacc4059859bb6653cca1527a22a.
Native codec bytes, custom-datapack startup and live behavior remain unverified.
