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

## Gather and craft

- Same dry floor/start position. Hold an ordinary undamaged iron axe. Exactly two
  oak logs at (33,-60,8)/(33,-59,8); no loose items or preexisting oak logs, oak
  planks or sticks in inventory. Empty 2x2 crafting grid/cursor, no open container.
- Mine both logs, approach and collect their actual drops, craft two log→planks
  operations and two vertical planks→sticks operations. Final inventory: logs0,
  oak planks4, sticks8; axe cost/drops recorded, no other inputs consumed.
- Direct arm uses physical mine/walk/pickup/menu/menu_click tools with completion
  checks and real 2x2 crafting slots/result pickups. Each result pickup performs
  one native craft; don't fabricate inventory or use scripting to bundle clicks.
- Script arm uses performance-gather-craft.js in one call. Native recipes, mining,
  pickup, cursor management and actual ResultSlot outputs still apply.
- Independently inspect removed log blocks, saved inventory/cursor/grid, axe
  damage and body position. Compare only successful complete outcomes. If fixture
  error makes an arm invalid, preserve it and identify that limitation explicitly.

No additional live run has been authorized by creation of this contract. The
lead coordinates a single later grouped comparison, with clean lifecycle handoff.

## Recorded wall pair — 042cc01

Both arms used Codex 6.1 Sol low and the same body at the actual observed start
(34.5, -60, 5.5). Independent native checks confirmed all eight stone targets and
zero remaining stone for both arms, with unchanged position/shield. No retry.

| Measurement | Direct tools | Prepared script |
| --- | ---: | ---: |
| Model-visible Minecraft calls | 18 | 1 |
| Native placement requests | 8 | 8 |
| Game ticks over measured task | 2,469 | 31 within script |
| Recorded tool/task interval | 123.557 s | 1.689 s for minecraft_run |
| Script execution | n/a | 1.225 s |
| Source-read through script completion | n/a | 7.721 s |
| Coordinator dispatch through result report | 169.190 s | 48.228 s |
| Bridge operations | not separately recorded | 19 |

Direct calls were two observations, eight placements and eight status checks,
one call per functions.exec invocation; no batches or extra polls. Each native
placement took one tick. Its interval includes model coordination and context
compaction, with the first timestamp recorded immediately before observation.
The scripting coordination interval starts before reading the prepared source,
not at exact instruction receipt. Consequently these are observed workflow
costs, not a controlled native-physics speedup or a universal latency ratio.
The useful established result is equal native work/outcome with 18→1 visible
Minecraft calls. The gathering/crafting pair is recorded below.

[Direct transcript](</Users/scott/.bb/thread-storage/performance-wall-direct-evidence.json>)
and [script transcript](</Users/scott/.bb/thread-storage/performance-wall-script-evidence-b583fc90.json>)
retain timestamps, exact code/calls and limitations. The coordinator independently
confirmed the target blocks and inventory after each arm.


## Recorded gather/craft pair — 042cc01

The same Codex model/body, two oak logs and fresh iron axe produced logs0,
planks4 and sticks8 in both arms. Each axe gained two damage; grid/cursor were
empty and the menu was closed. No measured retry or command use occurred.
Fixture preparation, including correcting an empty supply barrel before the
first arm, is outside the measurements. Independent native checks confirmed the outcomes; original item totals and
components, elytra and shield were restored. Crafted outputs were stored
separately; the world was saved and exact test JVM stopped.

| Measurement | Direct tools | Prepared script |
| --- | ---: | ---: |
| Model-visible Minecraft calls | 44 | 1 |
| Native action requests | 21 | 31 |
| Game ticks over measured task | 4,704 | 175 within script |
| Recorded tool/task interval | 235.226 s | 9.450 s for minecraft_run |
| Script execution | n/a | 8.915 s |
| Source-read through script completion | n/a | 14.542 s |
| Coordinator dispatch through result report | 304.936 s | 44.896 s |
| Bridge operations | not separately recorded | 63 |

Direct calls were two observations, 21 actions and 21 status checks, with no
batching or extra polls. Actions comprised two mines, one walk (native pickup),
one menu open, 16 clicks and one close. The prepared script uses the shared
crafting queue and its additional native clicks/state barriers. Its higher
native request count is included, not hidden: the demonstrated saving is fewer
model/tool round trips, not fewer native actions. Movement endpoints need only
collect the same real drops and satisfy the task, not match exact coordinates.

As above, instruction receipt timestamps are unavailable. Direct timing includes
model coordination; scripted coordination starts before reading its prepared
source. These are one observed pair, not latency distributions or a controlled
claim that Minecraft simulation itself became faster.

[Direct transcript](</Users/scott/.bb/thread-storage/performance-gather-direct-evidence.json>)
and [script transcript](</Users/scott/.bb/thread-storage/performance-gather-script-evidence-6b2cbb8c.json>)
retain exact calls, code, timestamps and output.


The coordinator also recorded dispatch-to-report intervals in both tables. These
include agent startup, preparation to invoke the tool, result inspection and
reporting; they are broader than either tool duration or script execution.
[Combined evidence and clean handback](</Users/scott/.bb/thread-storage/thr_xykqkgui57/performance-042cc01-summary.json>)
include independently checked native results and transcript-count verification.
