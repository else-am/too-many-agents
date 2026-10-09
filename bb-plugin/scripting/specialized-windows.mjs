import { emitWindow } from './windows.mjs';
// This adapter shares inventory's whole-operation queue and native cursor.
export function installSpecializedWindows(bot, io) {
  const { queueWindow, check, send, pickup, storeCursor, reserveCursor, transfer, move, sourceSlot, recover,
    requireValue: need, sameStack, isKnownActionError, decodeItem, assertReady } = io;
  const windows = new WeakMap();
  const furnaceType = type => ['minecraft:furnace', 'minecraft:blast_furnace', 'minecraft:smoker'].some(t => type.startsWith(t));
  const enchantType = type => type.startsWith('minecraft:enchant');
  const anvilType = type => /^minecraft:(?:(?:chipped_|damaged_)?anvil)$/.test(type);
  const merchantType = type => type === 'minecraft:merchant' || type === 'minecraft:villager';
  const request = (ctx, type, fields) => ({ type, menuId: ctx.id, generation: ctx.generation, ...fields });
  async function command(ctx, type, fields) { check(ctx); await send(request(ctx, type, fields)); check(ctx); }
  async function store(ctx) { await storeCursor(ctx, ctx.window.inventoryStart, ctx.window.inventoryEnd); }
  async function clear(ctx, slots) {
    await reserveCursor(ctx);
    for (const slot of slots) if (ctx.window.slots[slot]) {
      await recover(ctx, async () => { await pickup(ctx, slot); await store(ctx); }, () => slot);
    }
  }
  async function withCleanup(ctx, slots, work) {
    try { return await work(); }
    catch (error) {
      if (error.name === 'InventoryError' || isKnownActionError(error)) {
        try { check(ctx); await clear(ctx, slots); }
        catch (recoveryError) { error.recoveryError = recoveryError; }
      }
      assertReady(); // An unknown recovery outcome must supersede the known failure.
      throw error;
    }
  }
  async function take(ctx, slot) {
    const item = ctx.window.slots[slot];
    need(item, 'EmptySlot', `Nothing to take from slot ${slot}`);
    await recover(ctx, async () => {
      await reserveCursor(ctx, [slot]); await pickup(ctx, slot); await store(ctx);
    }, () => slot);
    return item;
  }
  function put(ctx, slot, itemType, metadata, count, sourceStart = ctx.window.inventoryStart, sourceEnd = ctx.window.inventoryEnd) {
    return transfer(ctx, { itemType, metadata, count, sourceStart, sourceEnd, destStart: slot, destEnd: slot + 1 });
  }
  function checkedItem(ctx, item) {
    need(item && typeof item === 'object' && Number.isSafeInteger(item.count) && item.count > 0,
      'InvalidItem', 'Expected an observed Item');
    sourceSlot(ctx, item);
    return item;
  }
  async function putExact(ctx, slot, item) {
    let remaining = item.count;
    for (let source = ctx.window.inventoryStart; source < ctx.window.inventoryEnd && remaining; source++) {
      const candidate = ctx.window.slots[source];
      if (!sameStack(candidate, item)) continue;
      const count = Math.min(candidate.count, remaining);
      await put(ctx, slot, item.type, item.metadata, count, source, source + 1); remaining -= count;
    }
    need(remaining === 0, 'InsufficientItems', 'The requested component-identical anvil item is no longer available');
  }
  function propertyArray(menu, length) {
    return Array.isArray(menu.properties) && menu.properties.length >= length && menu.properties.slice(0, length).every(Number.isInteger);
  }
  function decoded(entry, previous) {
    if (!entry || !entry.count) return null;
    const value = entry.componentMap instanceof Map ? entry : decodeItem?.(entry);
    need(value && typeof value.type === 'number', 'MissingItemDecoder', 'Merchant items require the native Item decoder');
    return sameStack(previous, value) && previous.count === value.count ? previous : value;
  }
  function syncTrades(window, menu, state) {
    const merchant = menu.merchant;
    if (!Array.isArray(merchant?.offers)) { window.trades = null; window.selectedTrade = null; state.ready = false; return; }
    const trades = window.trades ?? [];
    for (let index = 0; index < merchant.offers.length; index++) {
      const offer = merchant.offers[index], trade = trades[index] ?? {};
      const first = decoded(offer.baseCostA, trade.inputItem1), cost = decoded(offer.costA, trade.costA);
      const second = decoded(offer.costB, trade.inputItem2), output = decoded(offer.result, trade.outputItem);
      need(first && cost && output, 'InvalidMerchantOffer', 'Native offer is missing an input or output');
      Object.assign(trade, { inputItem1: first, inputItem2: second, outputItem: output, costA: cost,
        tradeDisabled: offer.outOfStock, nbTradeUses: offer.uses,
        maximumNbTradeUses: offer.maxUses, demand: offer.demand, specialPrice: offer.specialPrice,
        priceMultiplier: offer.priceMultiplier, xp: offer.xp, rewardExp: offer.rewardExp });
      trades[index] = trade;
    }
    trades.length = merchant.offers.length;
    window.trades = trades;
    window.selectedTrade = trades[merchant.selectedTrade] ?? null;
    if (!state.ready) { state.ready = true; emitWindow(window, 'ready'); }
  }
  function decorate(window, state) {
    const queue = work => queueWindow(window, work);
    if (furnaceType(window.type)) {
      for (const [name, slot] of [['Input', 0], ['Fuel', 1], ['Output', 2]]) {
        window[`${name.toLowerCase()}Item`] = () => window.slots[slot];
        window[`take${name}`] = () => queue(ctx => take(ctx, slot));
        if (name !== 'Output') window[`put${name}`] = (type, metadata, count) => queue(ctx => put(ctx, slot, type, metadata, count));
      }
    } else if (enchantType(window.type)) {
      window.enchantments = Array.from({ length: 3 }, () => ({ level: -1, expected: { enchant: -1, level: -1 } }));
      window.xpseed = -1;
      window.targetItem = () => window.slots[0];
      window.takeTargetItem = () => queue(ctx => take(ctx, 0));
      window.putTargetItem = item => queue(ctx => move(ctx, sourceSlot(ctx, item), 0));
      window.putLapis = item => queue(ctx => move(ctx, sourceSlot(ctx, item), 1));
      window.enchant = choice => queue(async ctx => {
        choice = parseInt(choice, 10);
        need(state.ready, 'MissingEnchantmentOptions', 'Native enchantment options are not available');
        need(choice >= 0 && choice < 3 && window.enchantments[choice].level > 0,
          'InvalidEnchantment', 'Enchantment choice is unavailable');
        const before = window.slots[0];
        need(before, 'EmptySlot', 'Enchantment target is empty');
        await reserveCursor(ctx);
        await command(ctx, 'menu_button', { button: choice });
        const result = window.slots[0];
        need(result && (!sameStack(before, result) || before.count !== result.count),
          'NoProgress', 'Native enchantment action did not change the target item');
        return result;
      });
    } else if (anvilType(window.type)) {
      const operate = (first, second, name) => queue(async ctx => {
        need(name == null || typeof name === 'string', 'InvalidName', 'Anvil name must be a string');
        need((name?.length ?? 0) <= 35, 'InvalidName', 'Name is too long.');
        checkedItem(ctx, first); if (second) checkedItem(ctx, second);
        await withCleanup(ctx, [0, 1], async () => {
          await clear(ctx, [0, 1]);
          // Keep caller order; Item.anvil's component/cost prediction is incomplete.
          await putExact(ctx, 0, first); if (second) await putExact(ctx, 1, second);
          await command(ctx, 'anvil_name', { name: name ?? '' });
          need(window.slots[2], 'AnvilResultUnavailable', 'Native anvil has no result for these inputs/name');
          await take(ctx, 2);
          await clear(ctx, [0, 1]);
        });
      });
      window.rename = (item, name) => operate(item, null, name);
      window.combine = (first, second, name) => {
        if (!second) return Promise.reject(Object.assign(new Error('combine requires two Items'), { name: 'InventoryError', code: 'InvalidItem' }));
        return operate(first, second, name);
      };
    } else if (merchantType(window.type)) {
      window.trades = null; window.selectedTrade = null;
      window.trade = (index, times) => queue(ctx => trade(ctx, index, times));
    }
  }
  async function trade(ctx, index, times) {
    const window = ctx.window;
    need(merchantType(window.type), 'NotMerchant', 'Expected a merchant window');
    index = parseInt(index, 10);
    const initial = window.trades?.[index];
    need(initial, 'InvalidTrade', 'Trade index is unavailable');
    const remaining = initial.maximumNbTradeUses - initial.nbTradeUses;
    times = times || remaining;
    need(Number.isSafeInteger(times) && times > 0 && times <= remaining && times <= 256 && !initial.tradeDisabled,
      'InvalidTradeCount', 'Trade count must be positive, available and at most 256');
    let completedTrades = 0;
    try {
      await withCleanup(ctx, [0, 1], async () => {
        await reserveCursor(ctx);
        for (let operation = 0; operation < times; operation++) {
          const offer = window.trades?.[index];
          need(offer && !offer.tradeDisabled && offer.nbTradeUses < offer.maximumNbTradeUses,
            'TradeUnavailable', 'Native trade is exhausted or unavailable');
          // MerchantMenu.tryMoveItems uses ItemCost.test (component predicates),
          // fills actual payments and preserves items. Repeat to refill safely;
          // never approximate those predicates from a representative cost Item.
          await command(ctx, 'select_trade', { index });
          const selected = window.trades?.[index];
          // Native autofill can give all of a shared payment item to A, starving
          // B. Split only A's surplus; the resulting native output still decides
          // whether B's component predicate accepts it.
          const missingSecond = selected?.inputItem2 ? selected.inputItem2.count - (window.slots[1]?.count ?? 0) : 0;
          if (!window.slots[2] && missingSecond > 0 && window.slots[0]?.type === selected.inputItem2.type &&
            window.slots[0].count >= selected.costA.count + missingSecond) {
            await put(ctx, 1, selected.inputItem2.type, selected.inputItem2.metadata, missingSecond, 0, 1);
          }
          const output = window.slots[2];
          need(selected && window.selectedTrade === selected && output && sameStack(output, selected.outputItem) && output.count === selected.outputItem.count,
            'TradeResultUnavailable', 'Native trade has no matching payable result');
          const uses = selected.nbTradeUses;
          await pickup(ctx, 2);
          need(window.trades?.[index]?.nbTradeUses === uses + 1, 'UnexpectedTradeResult', 'Native pickup did not confirm exactly one trade');
          completedTrades++;
          await store(ctx);
        }
        await clear(ctx, [0, 1]);
      });
    } catch (error) { error.completedTrades = completedTrades; throw error; }
  }
  function open(block, matches, name) {
    return bot.openBlock(block).then(window => {
      need(matches(window.type), 'UnexpectedWindow', `Expected ${name}, got ${window.type}`);
      return window;
    });
  }
  bot.openFurnace = block => open(block, furnaceType, 'a furnace-like window').then(window => {
    need(windows.get(window).properties, 'MissingFurnaceProperties', 'Native furnace properties are unavailable');
    return window;
  });
  bot.openAnvil = block => open(block, anvilType, 'an anvil');
  bot.openEnchantmentTable = block => {
    try { need(block?.name === 'enchanting_table', 'InvalidBlock', 'Expected enchanting_table'); }
    catch (error) { return Promise.reject(error); }
    return open(block, enchantType, 'an enchantment table');
  };
  bot.openVillager = entity => {
    const type = bot.registry.entitiesByName?.villager?.id ?? bot.registry.entitiesByName?.Villager?.id;
    try { need(type != null && entity?.name === 'villager', 'InvalidEntity', 'Expected a villager entity'); }
    catch (error) { return Promise.reject(error); }
    return bot.openEntity(entity).then(window => {
      need(merchantType(window.type), 'UnexpectedWindow', 'Expected a merchant window');
      need(window.trades !== null, 'MissingMerchantOffers', 'Native merchant offers are unavailable');
      return window;
    });
  };
  return { syncWindow(window, menu) {
    let state = windows.get(window);
    if (!state) { state = { ready: false }; windows.set(window, state); decorate(window, state); }
    if (furnaceType(window.type)) {
      if (!propertyArray(menu, 4)) return;
      const values = menu.properties.slice(0, 4);
      const [fuel, totalFuel, progress, totalProgress] = values;
      Object.assign(window, { fuel: totalFuel ? fuel / totalFuel : 0,
        fuelSeconds: totalFuel ? fuel * .05 : 0, totalProgress,
        progress: totalProgress ? progress / totalProgress : 0, progressSeconds: totalProgress ? (totalProgress - progress) * .05 : 0 });
      state.properties = values;
    } else if (enchantType(window.type)) {
      const valid = propertyArray(menu, 10);
      const values = valid ? menu.properties : new Array(10).fill(-1);
      for (let index = 0; index < 3; index++) Object.assign(window.enchantments[index], {
        level: values[index], expected: { enchant: values[index + 4], level: values[index + 7] } });
      window.xpseed = values[3];
      const ready = valid && window.enchantments.every(option => option.level >= 0);
      const wasReady = state.ready; state.ready = ready;
      if (ready && !wasReady) emitWindow(window, 'ready');
    } else if (merchantType(window.type)) syncTrades(window, menu, state);
  } };
}
