# Prepared non-boat vehicle fixture (not run)

Use `observe-vehicle-controls.js` once per approved family by changing only
`selectedFamily` to `horse`, `pig`, `strider` or `minecart`. Await the lead's exact
combined artifact and native authorization first. Preparation is not a native
pass. Do not repeat earlier boat or other gameplay suites.

All setup, inventory preservation, equipment and restoration happen outside the
script. Use only the isolated development world. Resolve exact native UUIDs and
observe one live named vehicle, stationary on support within ordinary interaction
reach of the grounded survival body. Name it `Vehicle fixture FAMILY`. The body
starts unmounted, controls released, no menu/cursor. Keep unrelated equipment and
belongings intact. Use a fresh fixture area, unique tags and no other riders,
nearby loose drops, water, hazards or existing fixtures in the route.

Provide a broad flat dry floor and loaded clear headroom for the full mount/body
boxes, movement and collision-safe dismount. Allow at least 24 blocks of runway
north of the start and several blocks on each side. The script looks at
Mineflayer yaw 0 (negative Z), applies forward input for 20 ticks, briefly applies
manual forward for five ticks, clears manual controls plus moveVehicle(0,0), then
dismounts. Use ordinary native speed; no injected velocity, accelerated attributes
or teleport during execution. Do not force gliding or synthetic passenger flags.

| Family | Required native fixture | Body steering equipment |
| --- | --- | --- |
| Horse | Adult minecraft:horse, tamed, saddled, grounded; generated AbstractHorse NBT uses Tame and SaddleItem | Empty main hand avoids incidental item interaction |
| Pig | Adult minecraft:pig, saddled, grounded | Actual carrot_on_a_stick held; no activation/boost |
| Strider | Adult minecraft:strider, saddled, dry solid ground with safe headroom; ordinary dry slowness is expected | Actual warped_fungus_on_a_stick held; no activation/boost |
| Minecart | Ordinary rideable minecraft:minecart, stationary on bounded, straight north_south rails over solid support; no powered rails, slopes or junctions | Empty main hand |

For minecart, provide enough ordinary rail length that the 28 input/release ticks
cannot reach the end, plus solid end barriers outside the measured segment. Check
native rail support and collision-safe dismount space. Keep other mobs away:
vanilla carts can automatically board nearby non-player bodies. The initial
unmounted prerequisite and final short dismount observation do not promise
permanent dismount while lingering inside automatic boarding range.

Use normal mount AI so the ordinary native travel call ticks; do not assume a
NoAI fixture works merely because the adapter temporarily enables AI inside that
call. Keep ambient conditions calm and verify the mount is not rearing/panicking
or already moving before execution. Native Pig/Strider ridden input is always
forward while the steering item is held: forward=0 does not promise stopping.
Horse/minecart momentum may also coast. Explicit clear/zero input and passenger
cleanup are asserted; exact stop position and stand coordinates are not.

The script uses getCustomName(), shared Entity/Vec3 objects, actual passengers,
equipment and getControlState(). Positive waitForTicks is the ordered control
drain; no guessed helper or artificial readiness marker is needed for these
guest-owned actions. It verifies real displacement above 0.2 blocks and records
initial/mounted/moving/cleared/final body and vehicle positions, velocity, yaw,
passenger UUIDs, events and inventory equality. Public mount/dismount/moveVehicle
and setControlState return void; await native observations instead of their return.

Independently capture native vehicle/body positions and Passengers while mounted
if practical, then final dismount, actual inventory/steering item/saddle and healthy
ticks. Native script-owned inputs must release at finish; observe cleanup without
claiming proxy passengers or a synthetic stop. Native acceleration state is not
exposed by a public guest getter: getControlState proves manual flags only, so
retain the native zero-input action and independent state evidence. Restore the
original selected item/equipment/mode from known observed state afterward.

Stop an affected family at the first real failure and retain phase/action/native
evidence. Never replay an unknown mount/control outcome. No horse jump, ownership,
rail slope/powered, cancellation or boundary matrix is included in this check.
