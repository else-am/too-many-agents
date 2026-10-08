# Wall completion and inventory convergence

Preauthored before the focused reference execution. This check retains the same
eight placements and fixture as `performance-wall.js`, with one explicit contract
adaptation: record inventory at placement-promise completion, then allow at most
40 physics ticks for inventory convergence. Pinned `place_block.js` waits for a
block update, not inventory synchronization. No extra placement or action retry is
permitted. The earlier immediate-assertion failure remains failed evidence.

Use `node tools/mineflayer-reference/scenarios.mjs --wall-convergence` with the
existing isolated loopback reference server/world. Start survival Reference at
(34.5,-60,5.5), with exactly eight held stone and clear target cells x32..35,
y-60..-59,z8 above stone support. No commands occur in the measured source.

Require each placement promise to leave the intended block as stone, eight total
placements, zero stone after the bounded wait, unchanged body position and no
open menu/cursor. Independently verify all eight blocks and absence of inventory
stone through server commands after the script. Retain literal source/hash,
timestamps, immediate/final counts, convergence ticks and protocol warnings.

This is a focused outcome comparison with the already recorded native wall run,
not a rerun of native performance, an exact paired-source claim, or proof that
upstream and native promise-time inventory visibility are identical. Do not
promote all placement APIs from this case. Failure stops this run; no protocol
patches, repeated actions or automatic scenario rerun.

## Paired native provenance review

The retained native code/arguments are byte-identical to the original failed reference source (SHA2566908c06ea9a4f93964e4f58fcfeb526d872d0f2e2bcb2cc54cca3ef53722ca0d). Native run b583fc90 completed eight place actions with eight-item cost and confirmed release. Later independent native markers/NBT verify all eight cells and no remaining stone; they do not establish promise-time observations. The subsequent reference-only bounded inventory wait changes the completion observation, so the original reference failure remains failed. Selected wall outcomes match; placement refusal, other faces/options and broader cancellation are not inferred from this task. [Exact review and raw artifact hashes](/Users/scott/.bb/thread-storage/thr_xykqkgui57/wall-pairing-provenance-review.json).
