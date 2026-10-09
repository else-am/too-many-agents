import { toNotch } from './items.mjs';
// Creative inventory API adapted to authoritative native slots.
import { encodeItemTransport } from 'minecraft-item-transport';

export function installCreativeInventory(bot, io, Item) {
  const { requireValue } = io;
  function prepare(slot, item, waitTimeout) {
    requireValue(Number.isInteger(slot) && slot >= 1 && slot <= 45,
      'InvalidCreativeSlot', 'Creative writes require inventory slots 1..45; slot 0 is a native result');
    requireValue(Number.isFinite(waitTimeout) && waitTimeout >= 0, 'InvalidTimeout', 'waitTimeout must be nonnegative');
    requireValue(item == null || item instanceof Item, 'InvalidItem', 'Expected an Item or null');
    // Capture caller values before queuing. Mutating the Item later cannot change this request.
    return { slot, item: encodeItemTransport(toNotch(item)) };
  }
  async function write(ctx, value) {
    io.check(ctx);
    requireValue(bot.game.gameMode === 'creative', 'CreativeRequired', 'Creative mode is required');
    await io.send({ type: 'creative_slot', menuId: ctx.id, generation: ctx.generation, ...value });
    io.check(ctx);
  }
  bot.creative = {
    async setInventorySlot(slot, item, waitTimeout = 400) {
      const value = prepare(slot, item, waitTimeout);
      await io.queueWindow(io.current(), ctx => write(ctx, value));
    },
    async clearInventory() {
      await io.queueWindow(io.current(), async ctx => {
        const slots = bot.inventory.slots.flatMap((item, slot) => item && slot > 0 ? [slot] : []);
        let completedSlots = 0;
        try {
          for (const slot of slots) { await write(ctx, prepare(slot, null, 0)); completedSlots++; }
        } catch (error) { error.completedSlots = completedSlots; throw error; }
      });
    },
  };
}
