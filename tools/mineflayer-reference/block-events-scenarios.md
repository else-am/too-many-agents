# Block event slice (preauthored)

- Real pinned block_actions.js + real PC1.21.1 Blocks: note_block state instrument
  and note, piston extend/retract with carried packet block type despite a changed
  current block; single chest/ender chest/shulker count changes; double chest in
  all four facings emits once from the right half with its actual left partner.
- Intentional corrections: unrelated successful block actions (bell, beacon,
  spawner, etc.) are not chest lids; repeated closed count does not replay a close.
  Pinned chestTypes reverses native left/right; emit the actual right half.
  Use real state properties rather than brittle metadata arithmetic. Missing or
  mismatched double-chest partners are not fabricated.
- Destroy stages 0/9 produce progress, values outside 0..9 produce End, preserving
  optional currently observed breaker Entity as the source's extra argument.
  No body/proxy self-breaker echo; absent breaker stays undefined.
- Native pending: only successful doBlockEvent calls emit; a rejected/stale block
  event does not. Same world, loaded position, active lease and native broadcast
  radii (64 block action / strictly <32 breaking) bound observations. Direct proxy
  packets reach only that body's active script, never human private packets.
- Native pending: real note trigger, piston extension/retraction, both halves of
  a double chest open/close, shulker/ender chest, another breaker versus own miner;
  direct proxy packet once; lease release/world departure clears queued records.
  Floods fail only the observing lease, never throw out through the world tick.
- Guest state: ordered native records drain once; bounded count-state cache and
  malformed input bounds; event tuples are emitted by the root only after blocks,
  entities and inventory are fully hydrated. No fake mutations or packet history.

## Source and integration notes

Native 1.21.1 anchors: `ServerLevel.runBlockEvents/doBlockEvent` broadcasts only
true trigger results at radius 64; `destroyBlockProgress` excludes the breaker
and uses squared distance <1024 in the same level. Packet writers carry unsigned
action/parameter bytes and a progress byte (pinned protocol reads progress i8).
`NoteBlock.triggerEvent` reads INSTRUMENT/NOTE from state; `NoteBlockInstrument`
provides serialized names matched against pinned `registry.instruments`, including
head instruments. NeoForge listener-only note rewrites are not persisted to the
block state/packet: this adapter exposes that observable state, like Mineflayer.
`ChestBlock.getConnectedDirection` joins RIGHT counter-clockwise from facing;
Chest/EnderChest/ShulkerBox block-entity `triggerEvent(1,count)` controls lids.

Pinned anchor: Mineflayer4.39.0 `lib/plugins/block_actions.js`. Its chestTypes
array swaps PC1.21.1's native left/right values, so the fixed callback's first
Block is the actual right half (the pinned bug reports actual left first).
Unrelated block types no longer produce bogus chest callbacks. Zero counts stay
remembered, so an equal close notification does not replay an event.

`installBlockEvents(bot)` returns `{update(records)}`; update returns callback
tuples without emitting. Root calls it after full hydration AND blockUpdate
notification delivery (beside sounds/particles), then emits each tuple. This lets
state replacement invalidate cached lid counts before the new native event.
Input `next.blockEvents`: action records carry position/blockId/blockName/action/
parameter and optional native instrument/note; break records carry position/
breakerId/stage. Native queue drains once and resets on lease claim/release.
256 records/frame/queue, 256-character native block names, 1024 remembered chest
positions; overflow fails the observing script, not gameplay. Missing guest
columns or missing/mismatched chest partner suppress callbacks instead of
inventing Blocks. Entity arguments use existing hydrated instances or undefined.

AgentHands never enters the player list, so ordinary ServerLevel broadcasts are
observed once by the mixin; only explicitly addressed proxy packets reach its
connection observer. Human private packets are not inspected. No blanket
same-tick dedup discards legitimate repeated notes/piston actions.

Focused result: real pinned plugin and real prismarine Block comparisons passed
note instruments/pitches, changed-block piston identity, lid count changes,
shulker, progress/end and shared/missing breaker arguments. All four double-chest
facings proved the documented native correction; irrelevant lid/repeated-zero
corrections and batch/cache bounds passed. Changed native classes compiled
standalone against existing generated artifacts/AT visibility overlay; packaged
mixin targeting and all live event callbacks remain unverified. Probe/evidence
are retained in thread storage; no game, build or lifecycle operation ran.
