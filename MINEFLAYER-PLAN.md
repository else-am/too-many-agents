# Mineflayer core scripting

Expose the applicable Mineflayer 4.39.0 gameplay API to scripts controlling existing native Minecraft bodies through the BB plugin. Minecraft 1.21.1 singleplayer with an integrated server remains the target.

## Scope decision — October 8, 2026

The user requested removal of Mineflayer Pathfinder and its associated implementation. `bot.pathfinder`, `goals`, `Movements`, selected-route execution, species-specific route prediction and route-only native hooks are outside this branch's scope. The original implementation and historical fixtures remain on `feat/mineflayer-api`.

Keep existing Minecraft navigation and physical walking tools, manual controls, mounted controls, core gameplay and shared execution safeguards. Removing Pathfinder does not imply a new scripted navigation API; that can be designed separately.

## Core requirements

- One script performs useful sequences, loops and conditional procedures without a model call per action.
- Preserve applicable Mineflayer signatures, object shapes, events and completion/failure semantics. Native Mob differences are explicit: no invented player hunger, respawn, socket or account APIs.
- Use actual native tools, inventory costs, reach, timing and permissions. Digging and placement do not automatically approach targets.
- Supply synchronous observations through a hydrated local cache. Action completion and events follow authoritative native state; missing blocks remain unknown.
- Bind execution to its BB thread, body and world session. Bound CPU, memory, output, queued work and lease duration. Cancellation/departure prevent later mutations. Unknown outcomes abort without replay.
- Keep live game access on its owning thread. JavaScript runs in the isolated worker, without host filesystem, network or credentials.
- Preserve native inventory, components, menus, crafting results and persistence. Do not copy upstream bugs that produce wrong targets or lost items.

## Completion work

Freeze feature expansion. Review core contract gaps, then run focused native acceptance on the combined packaged build. Reuse valid evidence; do not repeat successful suites without a changed dependency or concrete concern. Library checks and compilation do not prove native behavior.

Required remaining work includes affected lifecycle/unknown-outcome checks, selected event and action failures, current package/setup/persistence verification, and measured server-frame cost. Run only in the guarded development world, following AGENTS.md. Do not modify BB or the user's worlds.

Reference dependencies are pinned in `tools/mineflayer-reference/upstream.json`. The core catalog distinguishes implementation from evidence. No complete-compatibility claim is made until applicable gaps are resolved.
