# Remaining focused native fixtures — preparation only

These scripts have not run. A later explicit artifact/lifecycle authorization is
required. Use only the isolated development world. Preserve original mode,
difficulty, inventory, equipment and selected slot; fixture setup and restoration
are outside the guest calls. All uncertain actions stop without replay.

## Horse window

Prepare a uniquely tagged tamed adult ordinary horse named `Horse window fixture`,
with one real saddle, empty body armor and no passengers. Ground it on dry open
floor, within ordinary interaction reach of the unmounted body. NoAI is acceptable
for this inventory-only fixture. Observe exact UUID and native SaddleItem before
the call; avoid accidental lookup of old similarly named fixtures. No inventory
or equipment changes are required for the body. No window/cursor/sneak initially.

`observe-horse-window.js` sets native sneak before `openEntity`. Generated
AbstractHorse.mobInteract uses tame + secondary-use to open inventory. AgentHands
constructs the real HorseInventoryMenu; public window.type is `HorseWindow`, with
saddle/armor slots 0/1 and inventoryStart 2. Window is not an exported guest class,
so the script checks currentWindow identity/type and shared cursor Item instead
of inventing an instanceof HorseWindow check. It picks up the saddle, returns it,
closes and releases sneak. Independently re-read SaddleItem, body inventory,
cursor and menu state. Do not repeat with donkey/armor/invalid items in this slice.

## Inactive command block

The script's explicit target is (112,-60,64). Before authorizing its use, confirm
the location is disposable/clear or change this one constant to an approved
nearby fixture coordinate. Prepare an unpowered impulse command block with fixed
known facing, conditional=false, empty Command, TrackOutput=false, auto=false and
SuccessCount=0. No redstone source or executing chain may feed it. Body must be in
`creative_commands`, actual human must already have level-2 permission, and native
command blocks must be enabled. If these permissions are unavailable, report the
precondition; do not change server configuration or invent a dev hook.

`observe-command-block.js` checks default editing, then chain mode with
conditional=true/trackOutput=true/alwaysActive=false. The text `say Unexecuted
fixture` is written only: never power it or execute it. Sequence mode does not
run by itself. A final default empty edit restores impulse/default flags and
deliberately returns without await, testing finish drain. Native independent
NBT must confirm Command/TrackOutput/auto/conditional/facing at the stages where
available, and final empty/default impulse/facing/SuccessCount0. A transient
sample is not promised by the script; if coordination misses it, record that
limit. Restore original body mode and remove only the exact fixture afterward.

## Boss bar

Save difficulty. Use temporary non-Peaceful difficulty, since Wither.checkDespawn
discards it on Peaceful even with NoAI. Listener must start with no fixture bar.
After each NEW completed look action, perform the corresponding one native
operation: (1) summon one uniquely tagged Wither on safe dry floor >=8 blocks from
the body and inside its observed entity radius, NoAI:1b, Invul:0, known Health300,
styled CustomName {text:"Boss fixture first",color:"gold"}; (2) data merge that
exact resolved UUID with styled renamed CustomName {text:"Boss fixture renamed",
color:"aqua"}; (3) kill/remove only that exact fixture. Never guess UUID or use
the pre-invocation message as readiness. Save previous look ID before invocation
and observe each new completed ID in order. Allow a 240s guest deadline for the
three bounded 1200-tick waits. Native death/removal may take 200 ticks; no retry.

`observe-bossbar.js` checks shared BossBar/ChatMessage, hydrated list, stable object
through rename/deletion, finite native progress and no unchanged-frame replay.
Wither.setCustomName updates bossEvent directly even with NoAI. Do not assume
boss-event UUID equals Wither UUID or that NoAI updates progress from Health.
Record actual Wither UUID/NBT/name before and after rename, and final entity
absence. Native death loot may remain outside body pickup radius; preserve and
report it rather than deleting unrelated entities. Restore difficulty and mode.
No dragon/custom-recipient/range/health-change matrix belongs to this check.
