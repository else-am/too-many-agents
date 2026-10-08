# Public pinned registry data

Contract recorded before implementation:

- The public data catalog matches the installed Minecraft-data 3.117.0 PC1.21.1
  registry: all static tables, ordinary lookup indexes and version metadata.
  Foods describe items; they do not add hunger to the native Mob.
- Each lookup references its canonical array object. Check every block-state ID,
  item/food/effect/enchantment/attribute/entity/particle/sound/map-icon entry,
  instrument, window and loot entry, including sparse IDs and string keys.
- Version comparison operators and isOlderThan/isNewerOrEqualTo agree with the
  pinned source for every recognized PC version name and major alias. Unknown
  names reject. Only version-name/dataVersion numbers accompany the selected
  1.21.1 tables; no historical data loader or runtime host module is exposed.
- Build packaging retains one copy of each canonical table. In particular,
  blocksByStateId is reconstructed from ranges, avoiding an 18MB repeated JSON
  table. Browser dependencies must contain no all-version minecraft-data loader.
- Actual world biome IDs and chat formats still come from native observations.
  Static protocol/login/command/loot data are upstream reference data, not the
  live server's permissions, datapacks, login state or recipes. Reading them
  cannot send packets or grant command execution.
- Public loadDimensionCodec/writeDimensionCodec and richer native dynamic
  registry hydration remain separate pending work; this slice must not claim
  those methods exist or promote the entire Registry API to conformant.

Preimplementation comparison: exercise the actual pinned registry, record all
static keys and table/index identity plus exact version comparison outcomes.
Then compare the selected-table adapter in real QuickJS at 64MiB/512KiB. This is
library/build evidence only, not a native world or plugin interoperability run.

Implemented selected-table adapter: all 60 static source keys compare equal;
10,045 index-to-array references and 26,684 block-state references match; all
five comparison operators plus both version helpers match for 838 known names.
The actual QuickJS 64MiB/512KiB check passed at about 7.23MiB used for this isolated
registry fixture. Its 2.77MB bundle has zero external imports and no runtime
minecraft-data loader. This is not the memory measurement of an initialized
production bot with 25 full native columns. The root combined build/load remains
required after integration; live dynamic data methods remain pending above.
