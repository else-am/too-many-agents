# Native registry codec snapshot scenarios

Preauthored for the native begin-only `registryCodecs` snapshot. Native execution remains pending; standalone compilation is not registry/runtime conformance.

- Begin a script in the fixture world. Expect exactly `minecraft:dimension_type`, `minecraft:worldgen/biome`, and `minecraft:chat_type` objects, each with complete `{key,value}` entries and a typed anonymous compound. Compare each array index and resource key to the owning server registry's numeric ID; chat ID 0 must remain 0. No known-pack element omission. A later ordinary state snapshot must not resend this initial table.
- Decode each entry with that registry's native synchronized codec and the same registry serialization context; compare the meaningful native dimension flags/minY/height, biome climate/effects, and chat/narration decorations. Preserve NBT types and long values. Existing `chatFormattingById` stays available during guest integration.
- With a datapack defining a custom dimension type and biome, compare their actual registered IDs/names and custom dimension bounds/flags and biome temperature/downfall/effects. Do not assume vanilla packaged ordering or fabricate missing values. Snapshotting and guest table loading must not modify native registries or send packets.
- Invalid/sparse ID tables, missing native synchronized descriptors, encoding errors/non-compound outputs, or exceeding total entry/NBT-byte/node/depth limits must reject the snapshot, never shift IDs or silently truncate entries.

## Source contract

`RegistrySynchronization.packRegistries/packRegistry` encodes full elements with `RegistryDataLoader.SYNCHRONIZED_REGISTRIES` codecs and registry-aware NBT ops when no known pack is omitted. This helper selects only the three requested descriptors and explicitly walks numeric IDs. The biome descriptor uses `Biome.NETWORK_CODEC`: climate/effects are transmitted; world generation and mob-spawn tables are server-only and absent from the native network element. Dimension and chat descriptors use their `DIRECT_CODEC` definitions. `NbtIo.writeAnyTag` measures anonymous NBT encoding; `ScriptNbt.typed` preserves prismarine-NBT tag shapes and 64-bit values.

Bounds apply across all three tables: 16,384 entries, 4 MiB encoded anonymous NBT, 262,144 NBT nodes/primitive-array elements, maximum depth 64 (root depth 0). Native codec encoding constructs each tag before the projection limits are checked; this is not a sandbox for arbitrary mod codec execution. No global caches or runtime registry mutations.
