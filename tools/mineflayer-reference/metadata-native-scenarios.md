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
