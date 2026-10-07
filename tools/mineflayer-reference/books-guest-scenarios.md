# Guest books integration

`installBooks(bot, io)` installs async `writeBook(slot,pages)` and
`signBook(slot,pages,author,title)`, both resolving undefined. It has no imports or
new dependencies. Feed the existing inventory module's private helpers:
queueWindow, capture, current, check, send, click, select, reserveCursor, close,
requireValue, isKnownActionError, assertReady and snapshot. `click` is the approved
existing `(ctx,slot,button,mode)` helper; reserveCursor's existing optional excluded
slot array is used. This module creates no queue and never calls public queued
inventory methods internally.

Native prerequisite: edit_book accepts menuId/generation, raw hotbar slot,
expectedItemKey, pages and optional title. `check(ctx).slots` must carry Window
slot, native inventorySlot and the exact host-computed itemKey for every observed
stack (including empty entries if the host supplies those). Identity is taken
from the staged snapshot, never recomputed from a guest Item. Root send resolves
only after full hydration/events and poisons the shared queue on unknown outcome.

Workflow: validate/copy arguments; enter the shared queue; safely store unrelated
cursor; close an unrelated window and recapture inventory; use native SWAP with
hotbar0 for a non-hotbar book; select with the operation's ticket; edit; restore
original selection and both stacks. Compatible component-bearing book stacks are
swapped, never merged. Native permissions still decide whether staging/restoration
is possible (notably non-storage slots). Failures report partial observed state;
only known same-menu failures with unchanged captured keys may restore. Unknown
outcomes, poisoned queues, session expiry and generation replacement cause no
cleanup mutations. Cursor reservation stores it safely rather than reinstating
its prior held-on-cursor location, consistent with the shared inventory helper.

Public slots are integer Window coordinates0..44. Text bounds match the native
hook:100 pages, write1024 UTF-16 units/page, sign8192 units/page, title32 units.
Malformed values and sparse page holes reject before mutation. Native component
codec and total wire bounds remain final authority. Empty pages/title are valid;
null/absent signBook title rejects rather than accidentally writing unsigned.
Modern wire ignores the supplied author; native hands author is [TooManyAgents].
No optimistic NBT, component rewriting, invented author, arbitrary Item setter,
or waiting for a slot event on an unchanged write.

## Evidence and remaining live work

Preauthored reference run invoked actual pinned Mineflayer4.39.0 book.js and the
installed prismarine-windows click implementation over an explicit transport/
private-io fixture. It ran before guest implementation. Final bundled QuickJS
64MiB/512KiB passes17 ordinary source comparisons, the independently demonstrated
compatible-stack merge correction, and20 queue/error/bounds scenarios. Reference
and guest observations are separated from fixture-owned item state so upstream's
optimistic shallow NBT edits cannot mutate the simulated authoritative copy.
The native edit echo and private-io fixture are NOT native/production-queue proof.
No game/server/BB lifecycle/install operations ran.

Lead's combined live check should cover staged write then sign, component and
stack preservation, actual itemKey comparison/author, original selection/slot
restoration, and a known rejection plus unknown/session failure. Existing
books-native-scenarios.md supplies detailed native cases; none were run here.
Full native component serialization, shared queue integration, cursor capacity,
reach/session checks and packaged runtime behavior remain lead-owned evidence.
