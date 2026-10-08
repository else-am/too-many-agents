# Block entity and sign editor events (contract before implementation)

Pinned Mineflayer 4.39.0 blocks.js emits blockEntityData(typed Block) after a
block-entity update, and signOpen(typed Block) when the native editor opens.
Native data comes from client-visible update tags, not private container NBT.

- Open a real editable sign in reach; signOpen sees the hydrated shared Block,
  current text and location before activateBlock resolves. Reopening the same
  sign is a new event. Initial hydration and unchanged frames are silent.
- Update sign text, then await an ordinary action/tick barrier; exactly one
  observed blockEntityData callback reads the updated typed NBT/text. Observe a
  distant sign change in a complete column too; local/full-column overlap must
  not duplicate callbacks. Intermediate changes between frames are not invented.
- Waxed/denied/non-sign interactions must not fabricate signOpen. No event is
  emitted merely from setting a guest Block's NBT. Expired editor permission
  does not become a new grant, and the existing native write checks remain.
- Listener callbacks see all state hydrated; keep existing action completion,
  ownership, unknown-outcome and control-draining rules. Native cases are pending
  the next grouped live check; no broad gameplay rerun is required.
