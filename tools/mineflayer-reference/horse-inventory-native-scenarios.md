# Horse inventory (before implementation)

Pinned Mineflayer `inventory.js` represents `open_horse_window` as a generic
`HorseWindow`; ordinary Window inventory/click/transfer APIs apply. Native
`HorseInventoryMenu` has no registered MenuType. Its actual slots are saddle,
body armor, optional 3×columns storage, then 36 player slots. Use actual slot
count and native placement/pickup rules, not a fixed horse/chest size.

Use the guarded world with a nearby tamed adult horse and a chest-equipped donkey
or llama. Sneak then `await bot.openEntity(entity)`; do not silently turn ordinary
interaction into an inventory request when native behavior would mount/feed.

- Horse opens a hydrated `HorseWindow`, with saddle/armor at slots 0/1 and
  inventoryStart=2. Equip a real saddle and valid armor through native clicks;
  reject invalid items without loss. Independent native horse inventory and body
  armor must match. Cursor and both inventory projections must agree.
- Donkey/llama storage uses native column count and 36 player slots. Transfer a
  split component-bearing stack into storage and back. Close and reopen; native
  contents and slot mappings persist. No synthetic inventory or armor slot.
- Untamed/baby/occupied/distant/dead horses retain native interaction behavior;
  no container success unless one actually opened. Removing a horse/chest or
  leaving reach invalidates the menu. Stale Window/generation clicks reject.
- Script cancellation/world departure returns the real cursor through ordinary
  native close handling. No second menu owner, duplicate open event, item creation
  or retry of unknown interaction outcomes.

Mounted inventory via the player's separate inventory command remains pending;
this slice enables the existing native sneak-interaction path.


## Packaged selected result: b21ce69

Horse window PASS: exact observe-horse-window.js once, genuine HorseWindow inventoryStart2/38slots, saddle1 typed cursor pickup/return, native SaddleItem1 before/after; inventory unchanged, sneak released/menu closed/unmounted.6requests,19updates,15bridge operations,1537ms.

Executor Codex gpt-6.1-sol low; artifact SHA256
`e502233e1840adffaf3d6529749689c283e695264695e193a934edd70972c8ce`.
Exact source hashes, absolute call timestamps, raw/native evidence and limits:
`/Users/scott/.bb/thread-storage/thr_xykqkgui57/b21ce69-summary.json`.
