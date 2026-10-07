# Boss bars — scenarios recorded before implementation

Pinned oracle: Mineflayer 4.39.0 bossbar.js and plugins/boss_bar.js.

Library checks: native UUID, title conversion, health, seven colors/five division
counts, public setters and all eight flag combinations. Preserve stable bars
across updates; creation/update/deletion callbacks see the complete current list.
Repeated identical snapshots emit nothing. Initial snapshots populate silently,
as with existing observed state. Intentional correction: boolean flag fields and
round-trip flags use bits 1/2/4; upstream stores masks then shifts them again.
Use the actual shared ChatMessage class, not a new registry/class identity.

Native scope: existing bounded entity observation supplies Wither boss events;
EndDragonFight's actual validPlayer predicate is evaluated for the body. Only
visible native events are included. Custom bars require actual assignment to the
body's interaction proxy; do not copy the human client's recipient list or add a
fake network player. Broader entity tracking and custom-bar targeting remain
pending. Health/progress (including Wither charge), title and flags come from the
ServerBossEvent, not guesses from entity health. No mutation of boss recipients.

Focused native fixture (unrun): observe a named Wither, change its progress/title,
verify one stable bar and updated typed title during callbacks, then remove it.
Check creation/removal at observation boundary and native event visibility.
Compare End arena presence with actual validPlayer predicate; verify a custom bar
assigned only to Dev is not leaked. Empty snapshots must remove old bars. All
native reads run on server thread. Bound event records and encoded title bytes.
