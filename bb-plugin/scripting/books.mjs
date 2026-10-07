// Public book workflow adapted from Mineflayer 4.39.0 (MIT). See books.LICENSE.
// io is the existing inventory module's private queue/action helpers, not a new
// queue. Native edits and snapshots own all Item components and acknowledgements.
export function installBooks(bot, io) {
  const { requireValue } = io;
  function validate(slot, pages, title, signing) {
    requireValue(Number.isSafeInteger(slot) && slot >= 0 && slot <= 44,
      'InvalidBookSlot', 'slot out of inventory range');
    requireValue(Array.isArray(pages) && pages.length <= 100, 'InvalidBookPages', 'Book pages must be an array of at most 100 strings');
    const limit = signing ? 8192 : 1024;
    // Array.from also visits holes, unlike every/map on a sparse array.
    const copy = Array.from(pages);
    requireValue(copy.every(page => typeof page === 'string' && page.length <= limit),
      'InvalidBookPage', `Book pages must be strings of at most ${limit} UTF-16 units`);
    if (signing) requireValue(typeof title === 'string' && title.length <= 32,
      'InvalidBookTitle', 'Book title must be a string of at most 32 UTF-16 units');
    return copy;
  }

  async function write(slot, pages, title, signing) {
    io.assertReady();
    pages = validate(slot, pages, title, signing);
    await io.queueWindow(io.current(), async (ctx, ticket) => {
      const originalSelection = io.snapshot().hands.selected;
      const stagedSlot = slot < 36 ? 36 : slot;
      let staged = false, selected = false, bookKey, displacedKey;
      const entry = index => io.check(ctx).slots.find(value => value.slot === index);
      const key = index => entry(index)?.itemKey ?? null;
      const requireBook = () => requireValue(bot.inventory.slots[slot]?.type === bot.registry.itemsByName.writable_book.id,
        'NoBook', `no book found in slot ${slot}`);
      const requireKey = value => requireValue(typeof value === 'string' && /^[0-9a-f]{64}$/.test(value),
        'MissingBookIdentity', 'Book has no authoritative native itemKey');
      function verifyStaged() {
        io.check(ctx);
        requireValue(key(stagedSlot) === bookKey && (!staged || key(slot) === displacedKey),
          'BookChanged', 'Book or displaced stack changed during the operation');
        requireValue(!ctx.window.selectedItem, 'OccupiedCursor', 'Cursor changed during the book operation');
      }
      async function restore() {
        verifyStaged();
        if (selected) {
          requireValue(io.snapshot().hands.selected === stagedSlot - 36,
            'BookSelectionChanged', 'Selected hotbar slot changed during the book operation');
          await io.select(originalSelection, ticket);
          selected = false;
          verifyStaged();
        }
        if (staged) {
          // SWAP preserves compatible component-bearing book stacks as two stacks.
          await io.click(ctx, slot, 0, 2);
          staged = false;
          requireValue(key(slot) === bookKey && key(stagedSlot) === displacedKey,
            'BookChanged', 'Native book restoration did not restore both stacks');
        }
      }
      try {
        requireBook();
        requireValue(Number.isInteger(originalSelection) && originalSelection >= 0 && originalSelection <= 8,
          'MissingSelection', 'Native selected hotbar slot is missing');
        if (ctx.window !== bot.inventory) {
          // Protect source/hotbar stacks while moving an unrelated cursor to
          // safety. Container coordinates differ from player Window coordinates.
          const rawSource = slot >= 36 ? slot - 36 : slot >= 9 ? slot : 44 - slot;
          const excluded = io.check(ctx).slots.filter(value => value.inventorySlot === rawSource || value.inventorySlot === 0).map(value => value.slot);
          await io.reserveCursor(ctx, excluded);
          await io.close(ctx);
          ctx = io.capture(bot.inventory);
          io.check(ctx);
          requireBook();
        }
        await io.reserveCursor(ctx, [slot, stagedSlot]);
        bookKey = key(slot);
        requireKey(bookKey);
        displacedKey = key(stagedSlot);
        if (slot !== stagedSlot) {
          await io.click(ctx, slot, 0, 2);
          staged = true;
          verifyStaged();
        }
        await io.select(stagedSlot - 36, ticket);
        selected = true;
        verifyStaged();
        const info = entry(stagedSlot);
        requireValue(info?.inventorySlot === stagedSlot - 36, 'InvalidBookMapping', 'Staged book has no correct native hotbar mapping');
        await io.send({ type: 'edit_book', menuId: ctx.id, generation: ctx.generation,
          slot: info.inventorySlot, expectedItemKey: bookKey, pages, ...(signing ? { title } : {}) });
        io.check(ctx);
        const expectedType = bot.registry.itemsByName[signing ? 'written_book' : 'writable_book'].id;
        requireValue(bot.inventory.slots[stagedSlot]?.type === expectedType,
          'BookEditNotObserved', 'Native book result was not present after the action barrier');
        bookKey = key(stagedSlot);
        requireKey(bookKey);
        await restore();
      } catch (error) {
        // check/assertReady prevent ANY cleanup after poison, session departure
        // or menu replacement. A known failure may restore only captured stacks.
        if (staged || selected) {
          if (error.name === 'InventoryError' || io.isKnownActionError(error)) {
            try { io.assertReady(); io.check(ctx); await restore(); }
            catch (recoveryError) { error.recoveryError = recoveryError; }
          }
        }
        io.assertReady();
        throw error;
      }
    });
  }

  bot.writeBook = async (slot, pages) => { await write(slot, pages, null, false); };
  // PC1.21.1 sends no author; the native editing player supplies its identity.
  bot.signBook = async (slot, pages, author, title) => { await write(slot, pages, title, true); };
}
