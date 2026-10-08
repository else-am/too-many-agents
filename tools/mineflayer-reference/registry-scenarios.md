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
  registry hydration were separate work at this contract's authoring. They are
  now implemented under registry-codecs-scenarios.md; this static comparison
  does not establish their native behavior or full Registry conformance.

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
production bot with 25 full native columns. The combined Java/plugin/package build passed at `11ea771`. The actual production
guest bundle also loaded within QuickJS at 64 MiB / 512 KiB, using about 8.15 MiB before
bot initialization (279ms measured). No initialized columns/native snapshot
claim follows from that load. Dynamic data methods remain pending above.


## Coverage evidence boundary

For this PC1.21.1 target, fixed registry tables/indexes and version comparisons
are pure library contracts: their values and shared references are exhaustively
compared against the pinned data in QuickJS. They do not require a native packet
or physical action to exist. Review these members individually, excluding biome
indexes (overridden by native codecs), supportFeature (installed elsewhere), and
all dynamic codec methods/data. No library record promotes bot.blockAt, items
observed in inventory, native events or any physical action.

Before changing coverage policy, require: only explicitly reviewed fixed-data
keys can use library evidence; every pass still needs exact scenario/report
hashes, related subject and reviewed scope. Unlisted gameplay members retain
native and live-reference evidence requirements. Reject a library-only dig
claim even if its evidence files exist. Missing/tampered evidence must never
become supported. Historical results need not be silently treated as current.

A focused rerun after dynamic codec integration passed on cbb5e22 with the same
60 static keys, 10,045 index links, 26,684 state links and 838 version names.
Actual QuickJS reported 7,258,533 bytes used; bundle 2,777,577 bytes, no external
imports. Current source/report hashes are in registry-library-current evidence.
The catalog now reviews 54 fixed-data/version members as supported; native biome
indexes, supportFeature installation and dynamic codec methods stay separate.
No physical action/event is promoted by this result. Guard probes rejected an
unreviewed library-only dig declaration and tampered/missing reports.
