# Direct tools versus scripting: small task comparison

This is the performance comparison requested in MINEFLAYER-PLAN.md, not a
conformance suite. Run each arm once on the same packaged revision, native body,
Codex model/effort and isolated test world. Prepare/reset fixtures outside the
measured interval with guarded native commands. Agents may not use commands or
creative items during either measured task. Record errors and partial outcomes;
do not retry an unknown mutation or count a failed task as a speed improvement.

Record: model/effort, build/JAR hash, body/permissions/ambient mode, exact starting
inventory/position, start/end wall time, game ticks, model-visible tool calls,
native action count and bridge operations where available. Report wall time and
script execution time separately. Retain actual invocation transcripts; do not
infer call counts from what an ideal baseline would have done. If direct calls
are batched in one model turn, record that explicitly. No precision beyond useful
outcome/measurement accuracy is required.

## Wall

- Native survival body at (34,-60,5.5), dry solid stone floor under the fixture.
  Hold exactly eight stone blocks. Targets x32..35, y-60..-59, z8 are air; no
  entities obstruct them. Observe/check remaining inventory independently.
- Build exactly that four-wide, two-high wall, bottom row first. Both arms start
  already equipped and place by clicking each immediately lower supporting block
  with face up. No navigation, commands or extra material creation during task.
- Direct arm: ordinary minecraft_observe, minecraft_action(place) and action
  status calls, sequentially confirming completion. End with observation.
- Script arm: one minecraft_run using performance-wall.js. Source is prepared
  beforehand, but record the model turn that submits it as part of tool overhead.
- After each arm independently inspect all eight target blocks and native stone
  count 0, unchanged unrelated inventory and no other edited blocks. Reset the
  fixture before the other arm. Don't rerun either success for a better number.

The earlier gathering/crafting comparison used Pathfinder and is preserved on
`feat/mineflayer-api`; it is not an executable benchmark for this core branch.
