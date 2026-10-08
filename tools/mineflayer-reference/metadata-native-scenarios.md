# Raw metadata contracts (preauthored)

Use actual native DataValue wire and pinned host decoding; do not infer metadata
from convenience fields or consume SynchedEntityData.packDirty.

- First observation merges only native non-default keys. A default shared flag
  is absent; an actual NoAI/left-handed Mob flag is present. Change a seen flag
  back to its actual default and observe the same Entity and metadata object
  with that key reset, not missing/stale. Temporarily absent observations must
  not erase known keys while the guest retains that Entity identity.
- Custom name present then removed: typed anonymous NBT then the decoded absent optional
  (`undefined`, as pinned ProtoDef); getCustomName returns null again. A dropped Item Slot with component NBT/longs and item
  particles uses the corrected existing Slot codec, not synthesized Item data.
- Preserve the existing collected-item event snapshot: a Fox/player may empty
  DATA_ITEM before the collection callback, whose native originalItem capture
  still supplies the pre-pickup Slot. Apply that event-time value before any
  current observation, which always wins.
- Exercise native primitive/long/vector/quaternion, rotations, UUID/position,
  optional integer, villager/variant holder and particle serializer values.
  Optional GlobalPos retains dimension AND position; inline painting uses
  native1.21.1 varint width/height/assetId (no later title/author fields).
- Remap serializer and particle IDs in the observed maps without changing their
  names; decoding must select the same known codec. Native block-state/variant
  reference IDs stay native; Item IDs retain existing guest Item translation.
- Unknown serializer/particle codecs, duplicate keys, malformed/oversized wire,
  invalid non-default key lists and excessive aggregate work fail explicitly.
  Event-only records precede current records; current observations win, with
  initial non-default keys from either record retained if they share identity.

One focused host/native-generated-byte/QuickJS check covers the codec and sparse
merge contract. Native world observations above remain root-owned. Sampling
snapshots does not promise every intermediate vanilla metadata packet/event.

## Wire and hydration boundary

`ScriptMetadata.write` writes every actual `SynchedEntityData.DataItem.value()`
with `DataValue.write`, followed by 255, plus keys whose `isSetToDefault()` is
false. This does not consume `packDirty`. `ScriptItems.registries` supplies native
serializer IDs, particle IDs and variant-reference tables alongside Item maps.
`createMetadataDecoder` uses those maps in its four-entry codec fingerprint;
unknown custom codecs reject. Per entity: 255 entries/256 KiB. Aggregate metadata:
2 MiB per native observation encoder and per host frame (including events), with
1024 host entity records, existing 128-depth/200000-read decoder bounds.

Host transport uses the existing fully tagged Item tree. Guest hydration keeps
one seen-key set per Entity in a WeakMap. It merges initial nondefaults and real
values for already seen keys into the existing metadata object. Replacing an
Entity identity starts a new sparse history, matching the existing entityGone /
entitySpawn lifecycle; no strong cache keeps departed entities alive.
`entityUpdate` follows full hydration and precedes related crouch/sleep callbacks;
equal observations do not replay it. Event snapshots merge before current ones.
The existing native originalItem collection capture remains the event-time Slot
when a pickup already emptied the entity. It never overrides a later current
observation and does not manufacture a stack or packet history.

## Source corrections and focused result

Native anchors: `SynchedEntityData.DataItem.value/isSetToDefault`,
`SynchedEntityData.DataValue.write`, `EntityDataSerializers.getSerializedId`,
`GlobalPos.STREAM_CODEC`, `PaintingVariant.DIRECT_STREAM_CODEC` (1.21.1 generated
sources). Pinned anchors: Mineflayer `lib/plugins/entities.js` metadata handler
and `parseMetadata`; minecraft-data PC1.21.1 `entityMetadataEntry` and
`EntityMetadataPaintingVariant`; minecraft-protocol `src/datatypes/minecraft.js`
`readVarLong`.

Deliberate native corrections: GlobalPos includes dimension plus BlockPos;
painting width/height are VarInts with assetId and no later title/author fields.
Pinned `readVarLong` calls the 32-bit VarInt reader and turns native Long.MAX_VALUE
into -1. Metadata longs therefore use the existing lossless signed-long array
representation (`valueOf()` BigInt), including small longs consistently. Existing
Java modified-UTF8/NBT and nested Slot corrections remain shared with Item wire.
Absent optionals decode to `undefined` under the pinned protocol shape.

Focused standalone result: 14 actual native DataValue byte fixtures; eight exact
pinned codec matches. Six differences are the above three metadata corrections
and existing modified-UTF8 corrections in NBT, Item and Item particle fixtures.
Serializer/particle remaps, unknown codecs, duplicate keys, missing terminator,
invalid nondefault lists and byte bounds passed. Actual bundled merge/transport
passed QuickJS 64 MiB/512 KiB sparse initial/reset/same-object/event-before-current
checks and long/-0 preservation. Changed Java classes compiled standalone against
an AT visibility overlay; this is not a packaged/runtime transformer check.
Native fixture JVM only bootstrapped registries (no client, world or server);
NeoForge's normal built-in registry sync flags were enabled in that isolated
fixture because no mod-loader lifecycle ran. Live snapshot/event/collection
checks remain pending with the lead.

The added collection fixture also passed through actual `minecraftScripts`, the
real runner and QuickJS: native-empty Item metadata + actual captured three-item
Slot produced the event-time count 3, then current native-empty metadata reset
that same metadata object to count 0. Override is limited to playerCollect's
cause, native droppedItem capture and metadata key 8/type item_stack.

Lead integration (`09b6f0c`): normal full Java/plugin/package build passed with
the new access transformer; the existing shared item-wire suite passed 74
fixtures, 61 stock matches and 13 malformed-wire rejections. No client launched.
`observe-metadata.js` is the prepared real-body flag/default-reset/identity and
hydrated `entityUpdate` check; syntax parsed only. Packaged/native checks remain
pending executor availability.


## Prepared exact shared-flags default reset (unrun)

`observe-metadata-default.js` requires initial metadata key0 absent or exactly0
and records that distinction without inserting a synthetic default. It uses
ordinary synchronous sneak controls followed by positive waitForTicks, which
now drains preceding controls. Native transitions must be exactly2 then0,
with the same Entity/metadata object and retained key0 at0. Both entityUpdate
and crouch/uncrouch callbacks must see hydrated flags/convenience fields.
Five settled ticks must not replay these transitions; inventory/menu/cursor
and released controls are checked. This is not a claim about arbitrary
serializer/default-key resets. The earlier live3->1 result only removed crouch
while leaving another non-default flag; preserve that partial evidence.

For a future authorized run, independently inspect the actual body awake,
unmounted, grounded, dry and non-burning. Initial absent/zero flags alone do
not establish ongoing sun protection. A proposed guarded fixture is night or
an inspected temporary opaque roof covering the body with safe headroom,
followed by one native Fire reset to-1s before invocation and independent
Fire/position/ground/vehicle observations. Preserve binding/UUID/Health and
belongings; restore only created cells/time changes if authorized. Do not
assume saved ScriptProbe already meets this precondition, infer weather from
a later snapshot, or retry a failed control merely to obtain zero. No native
setup or execution is authorized by this preparation.

### Collection check on the future metadata artifact

Reuse `observe-mob-collection.js` unchanged for the changed metadata pipeline.
Its Fox case already requires getDroppedItem() to return shared Item paper1
with the original custom_name after native Fox split/take emptied DATA_ITEM;
it also checks collected Entity identity, hydrated Fox hand and exact one
event/no replay. Pinned Entity.getDroppedItem() constructs Item from the
actual metadata Slot. Thus the existing check directly exercises original
Item preservation through metadata; another raw-key assertion/script would
repeat that proof. The own-body case checks duplicate suppression and
authoritative inventory gain. This proposed reuse is specifically validation
of the new event-time metadata overlay, not a general collection rerun.
No live run, new collection script or production edit was made here.


## Selected live result (3d4043d)

Packaged 3d4043d PASS: genuinely absent initial flag key -> native sneak2 -> retained default0; stable Entity/metadata, hydrated callbacks, no replay/inventory changes. Exact evidence metadata-default-evidence-9b2a7e37.json. All dimensions saved; exact PID16206 stopped. Full evidence: `/Users/scott/.bb/thread-storage/thr_xykqkgui57/3d4043d-summary.json`.
