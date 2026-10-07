# Native book edit/sign scenarios

Authored before implementation from pinned Mineflayer4.39.0 book.js and generated
Minecraft1.21.1 / NeoForge21.1.251 sources. These are unrun native requirements;
no game, reference server, BB lifecycle, installation or EULA action is authorized.
Lead owns fixture creation, dispatch, snapshots and independent live observations.

## Hook and queue contract

Proposed package hook in AgentHands:

```java
JsonObject editBook(int menuId, long generation, int inventorySlot,
                    String expectedItemKey, List<String> pages, String title)
```

Null title writes; nonnull title signs. The hook uses raw Inventory coordinates
0..8 for hotbar and40 for offhand, not Window coordinates36..44/45. A hotbar book
must be selected when the action runs. Offhand is native-supported although the
pinned public book plugin only stages hotbar slots. It never moves stacks or
changes selection. The JS inventory queue stages/restores the book and selection
with native operations, carrying the current menu id/generation and the exact
native `itemKey` from the staged-slot snapshot. This is 64 lowercase hex digits:
SHA-256 of the base64 wire string encoded as UTF-8, exactly as host
prepareSnapshot computes it. Matching this full-state digest is a value guard
on item/count/component patch, not a new persistent UUID for indistinguishable
stacks. Do not regenerate it from a guest Item or substitute latest state after
an uncertain action. Root's existing session/lease/action-sequence barrier still
owns authorization/completion/cancellation; no second lease or async native edit.

Author is **not supplied by JS**. Modern edit_book sends only slot/pages/title;
ServerGamePacketListenerImpl.signBook uses the editing player's getName string.
For these hands the native profile is `[TooManyAgents]`; it is neither the user's
name nor the signBook author's argument. Preserve generation0, resolved=true,
literal page components and native item transmutation. No JSON/component parsing
of page text, title trimming, trailing-page erasure, or optimistic guest NBT.

Limits reflect serializable native components: at most100 pages; editable pages
at most1024 UTF-16 units; signed input pages at most8192 units plus the actual
written component codec's serialized-page bound; title at most32 units. Reject
oversize input before replacing any stack. Native packet bounds are looser
(200 pages,8192 units/page,128 title units), while the handler silently keeps only
100 pages and does not protect all component codec bounds. Reject these unsafe
cases instead of truncating or creating an unencodable snapshot/save. Empty pages,
empty page list and empty signing title remain native-valid.

Singleplayer IntegratedServer inherits MinecraftServer's TextFilter.DUMMY.
Pass-through text is therefore native behavior in the agreed environment. An
unexpected custom filter must be reported, never silently bypassed or awaited on
the server thread. General multiplayer/filter integration remains unestablished.

## Preauthored live matrix (all unrun)

Use independent native ItemStack component/NBT inspection, not snapshot echo as
the only oracle. Record raw slot, selection, menu generation, action sequence,
before/after item wire, and server saved hands data. All rejected requests must
leave the book/cursor/selection unchanged (native invalid-menu closure is a
separate preexisting lifecycle action).

| Case | Setup/action | Required independent result |
| --- | --- | --- |
| B01 write | Selected writable book; write two pages with newline, quotes, non-ASCII, emoji and JSON-looking text. | Same writable item/count; WRITABLE_BOOK_CONTENT is filterable raw strings, exact literal text; no authored title/written component. Repeat identical write completes without needing an updateSlot event. |
| B02 sign | After B01, pass new pages/title and arbitrary public author. | WRITTEN_BOOK, no WRITABLE_BOOK_CONTENT; exact literal Components, title, native hands author, generation0/resolved=true. Public method resolves void only after authoritative hydration; supplied author never spoofs identity. |
| B03 preservation | Writable book has custom name/lore, custom_data, unrelated added/removed component patches, and legal stack-count override. | Write preserves all unrelated state; signing uses native transmuteCopy semantics and retains count/patches except explicit book component changes and new item's defaults. No blank replacement stack. |
| B04 wrong item | Empty slot, written book, normal book, stone, disabled writable item. | Explicit rejection; no component edits, new items or other slot changes. |
| B05 stale state | Capture wire then change pages/custom name/count/slot item; change menu generation or close/reopen same id before queued edit. | Reject old identity or generation; do not modify replacement book/window. Restore logic never replays a lost edit. |
| B06 selection/slot | Edit selected hotbar0 and8; switch selection before execution; attempt slot9,35,36,-1,41; edit native offhand40. | Correct coordinate boundaries; wrong selected hotbar rejects. Offhand book changes without changing selection. Other inventory books require guest staging. |
| B07 text boundaries | 0/1/100/101 pages; null list/member; writable pages1024/1025; signed pages8192/8193; titles0/32/33; control characters whose signed Component encoding exceeds32767. | Valid boundary text persists exactly; oversize/malformed cases reject before mutation. No silent truncation, JSON injection, or later item-wire/save failure. |
| B08 guest staging | Book starts main inventory; hotbar0 occupied; original selection is5. Call writeBook then signBook. Repeat book initially selected/nonselected hotbar, full inventory, occupied cursor and recovery failure. | Native queue preserves displaced item and cursor, restores original selection/slot where safe; completion uses action barrier not optimistic Item edits. On known failure report actual partial staging; unknown outcome is never retried. |
| B09 lifecycle/busy | Body unload/session replacement, expired lease, mining/using item or invalid current menu before edit. | Existing lifecycle/busy/validity checks reject; queued edits never escape session. No external player inventory/XP/name changes. |
| B10 persistence/identity | Save/reconstruct hands after write/sign; try writing or re-signing final written item. | Components/native author/count survive; final written book is immutable to this hook. Identical value replacement is explicitly indistinguishable to wire guard. |

## Source anchors

- mineflayer/lib/plugins/book.js: editBook modern packet, write staging/restore,
  modifyBook's optimistic NBT, writeBook/signBook Promise<void> wrappers.
- Mineflayer docs/api.md writeBook: inventory Window slot coordinates and pages.
- ServerboundEditBookPacket.STREAM_CODEC: slot, page/title packet limits.
- ServerGamePacketListenerImpl.handleEditBook/updateBookContents/signBook:
  accepted raw slots, 100-page processing, Filterable conversion, transmuteCopy,
  native author and literal page Components.
- WritableBookContent.CODEC/STREAM_CODEC:100 pages,1024-unit page; WrittenBookContent
  CODEC/CONTENT_CODEC/STREAM_CODEC:32-unit title, flat serialized page bound,
  native generation/resolved fields.
- MinecraftServer.createTextFilterForPlayer, IntegratedServer (no override),
  TextFilter.DUMMY; ItemStack.transmuteCopy and save codec.

## Implemented handoff and validation

The hook above is implemented with `expectedItemKey` exactly as specified. It
returns the authoritative menu snapshot after marking inventory changed,
broadcasting player/current menus, and saving hands. Book state in the later
script snapshot still uses the shared ScriptItems encoder. Native action result
is not the public JS return value; writeBook/signBook resolve undefined after the
root's completion barrier and guest restoration sequence.

Known validation errors (all IllegalStateException): invalid_book_slot,
book_slot_not_selected, invalid_book_item_key, invalid_book_pages,
invalid_book_page_length, invalid_book_title_length, not_a_writable_book,
item_is_disabled, book_item_changed, invalid_book_components. Existing menu,
thread, body, idle-hands and session/lease errors still apply. Unexpected custom
text filtering raises book_text_filter_requires_async_support before mutation.
The full output ItemStack save codec and bounded native wire are preflighted on
a copy, including unrelated components. Codec errors omit content text; the
NeoForge save wrapper that logs complete failing components is not used for this
validation. Shared native wire's1MiB item bound also applies, so some aggregate
signed-page inputs below per-page limits can still reject without mutation.
No permanent identity UUID is added; exact-value ABA replacements are equivalent.

Java21 javac passed against the lead's existing generated NeoForge21.1.251 JAR,
project classes, and ScriptItems/ScriptNbt source; git diff --check passed. This
establishes source/API compatibility only. No unit tests were added after
implementation, and no native fixture/reference client/server/game/BB lifecycle
ran. B01–B10 remain unrun lead-owned checks, especially selection restoration,
metadata/component preservation, actual author, oversized rejection and save/load.
