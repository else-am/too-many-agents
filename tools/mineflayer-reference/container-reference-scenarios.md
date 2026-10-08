# Focused container reference task — preauthored

Run once on the existing isolated loopback reference server with unpatched pinned
Mineflayer 4.39.0. No native client or saved ScriptProbe changes. Fresh chest
(12,-60,2): slot0 stone60, slot1 stone20. Reference player (10.5,-60,2.5), empty
inventory except diamond pickaxe in hotbar6. Clear floor/air/reach and survival.

inventory-chest-common.js preserves the historical native task's windowOpen
hydration, synchronous selection, source-range transfer20, deposit10 merge64+6,
withdraw7, and one close. Verify final chest63/player17, empty cursor, held pickaxe
and one bot/window close with independent server block/inventory NBT predicates.
Retain exact source/hash, timestamps, raw result and any protocol/client warnings.
No retry or upstream protocol/library changes if it fails.

Two explicit adaptations from inventory-chest.js: check the current Window's
inventory region before close, then bot.inventory after close; retain the
pre-close bot.inventory count as an observation. Pinned inventory.js copies the
container inventory into bot.inventory in closeWindow, whereas native hydration
already maintains the mirror. The native stale-handle WindowChanged check is not
executed against upstream's retained window handle; it is a separate native
safety contract, not asserted as reference parity. No weaker native behavior is
introduced. Historical native result/independent NBT prove the task outcome but
lack embedded executed source, so do not claim an exact paired-source run.

This establishes a focused real container task, not every transfer option or
component/permission/race behavior. Setup and independent observations use the
server console; the measured task uses only the public API. The server is saved
and stopped by the existing harness finally block.

## Actual focused reference result

Passed once at 2026-10-08T09:43:28.083Z–09:43:28.633Z. Chest slot1 stone63,
player stone17, active-window inventory17 but pre-close bot.inventory0; selection6
and diamond pickaxe retained, empty cursor, bot open/close and Window close each1.
All four independent server NBT predicates passed. Client error list empty; the
existing startup ArmorTrimMaterial PartialReadError remains recorded. The server
stopped and port25575 was independently unbound. No retry or native invocation.
Exact source/report and startup log are in thread storage/reference-container.

## Historical source recovery

Exact completed BB call dad8728ab1-i72 (2026-10-07T22:03:37.567Z–22:03:40.217Z) supplies the actual native source, SHA256b8b48e688a4be3f28eb6b9c307c7a11dcb4f3fda6196a093c33179dd675ee5fb, and a result exactly equal to inventory-chest-native.json. It completed23 requests/46 updates with confirmed release. This supersedes the earlier missing-source limitation. Native pre-close mirror and stale-handle checks remain distinct from the reference; whole-source byte equality and JAR identity are not established. Saved independent native inventory confirms stone17/selection6/empty cursor and recent-chat block NBT confirms chest slot1 stone63; those auxiliary producer arguments/timestamps were not recovered. The unchanged inventory.mjs since tested268f8f0 and its20 fixture groups provide the remaining guest orchestration checks. No scenario reran. [Raw recovery](/Users/scott/.bb/thread-storage/thr_xykqkgui57/inventory-chest-provenance-recovery.json).
