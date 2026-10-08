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

The installed Mineflayer package was subsequently compared file-for-file with
the lockfile's npm tarball after verifying its SHA-512 integrity. All packaged
files matched (4.39.0); no installed-file drift was found. Evidence:
`/Users/scott/.bb/thread-storage/mineflayer-installed-integrity.json`.
The opt-in `--craft-trace` harness was then used for the single diagnostic
execution below. Its failed report is indexed as `reference-craft-diagnostic`
with scenario, executed-source and report integrity checks.

## Executed diagnostic: 2026-10-08 05:49 UTC

One `--craft-trace` run reproduced the final assertion failure. It captured
49 transitions without truncation, then saved/stopped its own reference server;
port25575 was no longer listening. No upstream edits, click replay, or native
port rerun occurred. Full evidence is retained in
`/Users/scott/.bb/thread-storage/reference-craft-diagnosis/`.

The zero-based trace establishes a concrete ordering failure:

- Entries8–12: first output pickup receives result-slot empty at state11; the
  client stores planks and starts the next log insertion using state11.
- Entries13–14: a slot4 empty correction and full inventory state13 arrive.
  That full inventory has no crafting input/output and cursor empty, with the
  remaining log still in storage slot9.
- Entry15: before the later actual result arrives, the client sends an output
  pickup at state13 claiming four planks on its cursor. This follows the
  crafting plugin's local output prediction and a result-slot listener that
  can also be satisfied by full inventory updates.
- Entries16–17: the server sends cursor log1 at state14, then the actual
  four-plank result at state15. Further clicks proceed amid corrections.
- Final authoritative full-window state35 contains stick4 as the crafting
  result, planks1 in each input slot1/3, storage planks4 and cursor stick4.
  The server's independent entity-NBT read confirms storage planks4 and axe
  damage2; player NBT does not include the transient crafting grid/cursor.

This directly demonstrates reference prediction/resynchronization interleaving
in this run. It does not prove every packet of the older run followed the same
sequence, nor turn either failed procedure into a pass. The port's existing
native result inspection and per-action ordered-state barriers intentionally
avoid this failure. Do not replace them with upstream optimistic slot writes.
No causal connection to the startup ArmorTrimMaterial warning was established.

## Focused wall convergence: 2026-10-08 09:55 UTC

The preauthored `--wall-convergence` check ran once on the unmodified pin. It
retained all eight placement calls and block assertions, recorded the immediate
stone count, then allowed at most40 physics ticks for inventory convergence.
At placement-promise completion the count was1; after one physics tick it was0.
All eight blocks and absence of inventory stone were independently confirmed
by the server. Position stayed (34.5,-60,5.5), with no menu or cursor item.

This run directly establishes the distinction between block completion and
inventory visibility. It does not retroactively prove the older packet sequence,
erase its failure, or establish identical native/upstream promise-time state.
The native wall was not rerun. Literal source, absolute timestamps, server
confirmations and warnings are retained in `wall-convergence-reference.json`
and `/Users/scott/.bb/thread-storage/reference-wall-convergence/`.
The measured source completed in1950ms/36ticks; these reference timings are not
a new native performance comparison. The server stopped and port25575 closed.
