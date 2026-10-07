# Native Entity / Chat hydration fixture

Authored before `ScriptEntities.java`. **Not executed by the implementation
agent.** The lead runs this only in the authorized isolated development world,
after integrating snapshot encoding, host decoding, guest classes and hydration.
No reference server or EULA acceptance is implied. This is observation coverage,
not chat event or full Entity conformance.

## Setup

Create exactly these entities within the existing observation radius. Keep the
agent away from the drop and avoid unrelated entities with these fixture names.
These vanilla 1.21.1 commands are proposed setup for the lead, not commands run
by the author. Run them with an origin near the bound body in the guarded world.
Component payloads use the native persistent component codecs; hydration must
use native stream codecs, not reuse these command strings as expected snapshots.

```mcfunction
summon minecraft:armor_stand ~2 ~1 ~ {Tags:["script_entities_fixture"],NoGravity:1b,Invulnerable:1b,CustomName:'{"text":"Fixture ","color":"gold","extra":[{"text":"subject","bold":true}]}',HandItems:[{id:"minecraft:diamond_pickaxe",count:1,components:{"minecraft:damage":3}},{id:"minecraft:shield",count:1}],ArmorItems:[{id:"minecraft:iron_boots",count:1},{id:"minecraft:iron_leggings",count:1},{id:"minecraft:iron_chestplate",count:1},{id:"minecraft:iron_helmet",count:1}],Passengers:[{id:"minecraft:armor_stand",Tags:["script_entities_fixture"],NoGravity:1b,Invulnerable:1b,CustomName:'{"text":"Fixture passenger"}'}]}
summon minecraft:item ~3 ~1 ~ {Tags:["script_entities_fixture"],NoGravity:1b,Invulnerable:1b,PickupDelay:32767s,Age:-32768s,CustomName:'{"text":"Fixture drop"}',Item:{id:"minecraft:diamond_pickaxe",count:1,components:{"minecraft:damage":3,"minecraft:enchantments":{levels:{"minecraft:efficiency":3}},"minecraft:custom_name":'{"text":"Fixture pickaxe","color":"aqua"}',"minecraft:lore":['{"text":"Fixture lore"}']}}}
```

Independently inspect `/data get entity` for each fixture tag/type, including
`CustomName`, `Item`, `HandItems`, `ArmorItems` and `Passengers`. Capture those
observations beside the script result. Check the setup actually took effect
before interpreting a script failure. Run `observe-entities.js` through the
normal agent script bridge. It checks rich ChatMessage conversion, a nonempty
dropped damaged/enchanted/named/lore Item, six ordered equipment Items, empty
slots, passenger/vehicle object links, and stable Entity/Vec3/equipment Item
references over three snapshots. Native enchantment IDs are world registry IDs;
the script does not assume the pinned language registry's enchantment ID order.

After the first pass, clear the stand's name through a native setter, clear its offhand and dismount the
passenger on the owning server thread. On a new snapshot check getCustomName()
returns null, offhand is null, passenger.vehicle is null and stand.passengers is
empty, without replacing still-observed Entity/Vec3 objects. In a separate phase
change the drop's damage from 3 to 7 and verify getDroppedItem() reads 7; do not
assert identity of successive getDroppedItem() results (upstream constructs them
fresh). Remove the fixture entities by the exact fixture tag after validation.

## Snapshot / contract checks

Before bridge integration, inspect the serialized fields independently:

- `customName` is absent when unnamed, otherwise prismarine typed anonymous NBT.
  Decode it with the actual pinned ChatMessage.fromNotch; do not put JSON text or
  a plain display-name string at metadata[2]. Null there throws upstream.
- `droppedItem` is absent for other entities, otherwise `{wire: base64}`. All six
  `equipment` entries are `{wire: base64}`; empty stacks decode to null.
  Map order: main hand, offhand, feet, legs, chest, head. The native animal BODY
  equipment slot is additional protocol data outside this requested six-slot
  projection; capture it separately if gameplay needs it, never map it to head.
- Compare equipment/drop wire values using the trusted Item decoder and upstream
  Item.fromNotch. Reuse ONE ScriptItems instance for body inventory plus all
  observed entity stacks so aggregate bounds apply. Oversize encoding must fail,
  not truncate or replace real items with empty values.
- `vehicle` is an actual native entity ID or null; `passengers` is ordered direct
  passenger IDs. Resolve only IDs already observed, without enlarging the radius.
  The lead must explicitly handle unresolved IDs rather than fabricate entities.
- `chatFormatting` is keyed by the actual world's CHAT_TYPE numeric IDs. Compare
  every entry to that registry's chat decoration translation key and parameter
  sequence, including a datapack type/reordered IDs when available. The guest
  registry property is `chatFormattingById`. No static/vanilla-ID default table.
  Source from `chat()` rather than `narration()`. Pinned fromNetwork ignores the
  decoration's style (including vanilla whispers' gray/italic); this projection
  preserves its public input contract, not styled native chat event rendering.

Negative checks for lead integration: call each helper off the server thread
and require explicit failure before serialization; test a name clearing update,
a nonliving empty equipment snapshot, unknown/unobserved relationship targets,
and shared encoder budget exhaustion across inventory plus entity items. A
client-side entity must be rejected. No helper may query/expand nearby entities.

## Source basis

Locally generated NeoForge 21.1.251 / Minecraft 1.21.1 sources:
`Entity.getCustomName/getVehicle/getPassengers`, `LivingEntity.getItemBySlot`,
`EquipmentSlot`, `ItemEntity.getItem`, `ComponentSerialization.CODEC`,
`ByteBufCodecs.fromCodecWithRegistries`, `ChatType.chat`,
`ChatTypeDecoration.translationKey/parameters`, `Parameter.getSerializedName`.
The component stream codec uses the same CODEC with registry-aware NbtOps; no
custom-name flattening or hand-built component translation is required.

Fixture correction from live execution: vanilla `data remove ... CustomName`
removes the saved tag but Entity.load leaves the existing name unchanged when
the tag is absent. It does not exercise setCustomName(null). Use a native setter
for the clearing case; command-driven name updates can verify replacement, but
must not be reported as clearing. Offhand clearing, dismounting, and dropped-item
damage changes were independently observed in the first command-driven phase.
