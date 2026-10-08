# Placement refusal — preauthored

One fresh isolated reference fixture, not a replay of the wall procedure. Player
at48.5/-60/5.5, survival, one held stone. Stone floor y-61, clear surrounding air.
A single stationary NoAI cow tagged tma_placement_refusal occupies the air cell
48/-60/8 above the selected support48/-61/8. The cow's actual collision volume
must overlap the destination; the target remains air. No test-client world,
ScriptProbe, launcher or BB change. Existing accepted reference EULA applies.
The isolated server must have spawn-animals=true: vanilla otherwise discards
even command-summoned cows. This is a startup prerequisite, not a client delay.

Exactly one public placeBlock(support,up) call must reject. Record its actual
error without prescribing Node/native error class or message equality. It must
not claim success or emit blockPlaced. The destination stays air, the held stone
and inventory stay unchanged, and no recovery placement is attempted. Independently
check target air, support stone, stone count1 and the live tagged cow on the
server after the measured call. Console setup/observations are not measured API
actions. Preserve source/hash, timestamps and protocol warnings on failure too.

Use the existing harness's bounded wait and saved shutdown. Remove only this
fixture's tagged cow before shutdown; do not rerun if the assertion fails. This
is one ordinary entity-obstructed top-face refusal, not six-face/placement-option,
permission, unloaded-state or cancellation coverage. A corresponding native
check remains unrun and requires independent native fixture/reach/body inspection;
do not infer native refusal from this reference result.

## First setup result

2026-10-08T11:06:34.268Z–11:06:34.559Z stopped before measured invocation:
the client could not observe the required cow. Server log confirms summon then
no entity at cleanup. server.properties had spawn-animals=false; generated
ServerLevel.shouldDiscardEntity (line463) explicitly discards Animal/WaterAnimal
when that server option is false. This establishes a setup defect, not a placement
failure. No placeBlock call, item cost or success/refusal assertion was reached.
The server saved all dimensions and stopped; port25575 was unbound. Existing
ArmorTrimMaterial decoding and setup teleport warnings remain in the report.
The harness now rejects this incompatible configuration before launching. No
configuration was changed, and this failed setup was not replayed. A future
run needs an explicit temporary isolated-server setting with restoration, then
the first measured refusal attempt; keep this original failure artifact.

## First measured refusal result

2026-10-08T11:13:03.183Z: after temporarily enabling animal spawning in the
isolated reference server, the unchanged public procedure ran once. It rejected
with `Server refused to place stone at (48, -60, 8): the block is still air`
in 156ms. No blockPlaced event, item cost or inventory change occurred. Four
independent server conditions confirmed air, support stone, retained stone and
live tagged obstruction. The cow was removed, all dimensions saved, the owned
server exited and port25575 was unbound. Original server.properties bytes were
restored exactly. The earlier setup failure remains separately indexed; no
measured placement was replayed. Existing ArmorTrimMaterial protocol warning
remains recorded. Native counterpart and other faces/options remain unverified.

Evidence: /Users/scott/.bb/thread-storage/placement-refusal-corrected/result.json
and configuration.json, server.log, console.log, source.js in the same directory.

## Native counterpart — prepared, unrun

Review at 8dacd40 found no concrete placement implementation gap:
AgentActions dispatches place to AgentHands.useBlock, which invokes native
ServerPlayerGameMode.useItemOn and saves actual hands in finally. The completed
action includes InteractionResult; bot.action waits for completedActionSequence
hydration. actions.mjs rejects explicit failed interaction or an unchanged
requested destination before emitting blockPlaced. These are source facts, not
runtime evidence.

Use one NEW empty adult bound cow body and a distinct NEW unbound stationary cow
obstruction. Never use ScriptProbe. Inspect the proposed coordinates, loaded
columns, body bounds and original cells before setup; do not overwrite old
fixtures. Body feet48.5/-60/5.5, obstruction48.5/-60/8.5, support48/-61/8,
destination48/-60/8. Actual body eye position, native reach and a visible support
UP face must be established. Native NoAI on the obstruction holds the fixture;
no body reach/geometry/attributes/collision changes are permitted. Give the
empty disposable body exactly one real stone and equip it through normal tools.
Record actual native inventories and both entity identities before execution.

Concatenate placement-refusal-native-prefix.js, a newline, then the unchanged
placement-refusal.js. Substitute only the two exact fresh UUID placeholders.
Retain the entire executed source/hash before invoking it once through the
bound Codex test thread. The prefix is a prerequisite check, not another
placement attempt. The measured suffix is byte-identical to the reference.
A failed prerequisite or action ends the phase; no automatic retry or repair.

Independently require one NEW native place action ID, completed with detail
interaction_finished_check_result and a refusal InteractionResult, along with
unchanged target air, support stone, stone count1, cursor/menu and obstruction.
A before-start rejection, target_out_of_reach, block_face_obstructed, unknown
outcome, or missing native ledger does NOT prove entity-obstructed refusal even
if the public procedure reports a caught error. Retain the actual error class
and message; they need not equal Node's. Verify script release separately.
Only after a known terminal outcome restore/remove the owned fixture and archive
the disposable body through supported operations; preserve evidence on unknown
outcomes. Save/stop the exact packaged client under the existing lifecycle rules.

The composed program has only been syntax-parsed. Display availability, exact
packaged artifact authorization and native prerequisite inspection remain
required. No native call or lifecycle operation was performed in preparation.
