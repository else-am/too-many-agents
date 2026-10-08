# Retained reference failures: source review

This review does not rerun a scenario, change upstream code, or promote a failed
procedure to passing evidence. Reports remain in ignored `run/mineflayer-reference/`.

## Wall

`wall-reference-failed.json` records successful placement of all eight blocks,
followed by `Wall material consumption differs`. Independent saved-world checks
confirmed eight stone blocks and no remaining stone in the player inventory.

The currently installed Mineflayer placement plugin waits for the destination
`blockUpdate`, checks the resulting block, emits `blockPlaced`, and resolves.
It does not await inventory synchronization (`place_block.js:11–48`). Therefore
an inventory assertion immediately after `placeBlock` is stronger than this
completion contract. This supports a timing explanation, but the old report has
no inventory packet timeline and does not prove the exact race. The physical
outcome matched; promise-time inventory equivalence remains unproven.

## Gather/craft

`gather-craft-reference-failed.json` reached its final assertion. Independent
saved inventory contained axe damage2 and planks7, rather than planks4/sticks8.
This is an actual outcome mismatch and cannot be cleared by a later inventory
observation or the wall explanation.

The current crafting plugin writes predicted output and remainder slots locally
(`craft.js:135–170`). Current inventory code also waits for result-slot updates
for crafting input AND result clicks (`inventory.js:476–489`). Consequently,
claiming that the installed code simply never waits for crafting responses would
be incorrect. The retained failure lacks per-click packet/state/grid/cursor
history; neither these source observations nor the unrelated startup
ArmorTrimMaterial warning establishes its cause.

## Next useful evidence

Before any additional reference execution, establish the exact dependency source
used by that run, then retain server-authoritative grid/cursor/inventory and
incoming/outgoing click/result transitions for the distinct failing craft
contract. Preserve the original failure. Do not rerun successful native building
or rewrite the reference library to obtain a pass. A future wall comparison
should distinguish block-promise completion from bounded inventory convergence.

## Source fingerprint for this review

These hashes identify the installed files read during this review. They are not
retroactive proof of the files used by the historical failed process.

- `place_block.js`: `6d9941df1725432adb12c3a439de4806077c134e569b3af78a80882bd2737d1b`
- `craft.js`: `5c896528cf6364642df182b1678884ed1e7bbbc552856c461fe77fdd21c79551`
- `inventory.js`: `c7c4eb12a4ba606b2abd6ea62701933fd10f3751297590e6b2ee56d78798b4a8`
