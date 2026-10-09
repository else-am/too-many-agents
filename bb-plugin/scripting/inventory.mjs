import { emitWindow } from './windows.mjs';
import { installCreativeInventory } from './creative.mjs';
import { installBooks } from './books.mjs';
// Every mutation is native and authoritative; never call Window.acceptClick.
import { Vec3 } from 'vec3';
import { installSpecializedWindows } from './specialized-windows.mjs';

const CLICK_TYPES = ['PICKUP', 'QUICK_MOVE', 'SWAP', 'CLONE', 'THROW'];
const STORAGE = ['generic', 'chest', 'dispenser', 'ender_chest', 'shulker_box', 'hopper', 'container', 'dropper',
  'trapped_chest', 'barrel', ...['white', 'orange', 'magenta', 'light_blue', 'yellow', 'lime', 'pink', 'gray',
    'light_gray', 'cyan', 'purple', 'blue', 'brown', 'green', 'red', 'black'].map(color => `${color}_shulker_box`)];
const MAX_CLICKS = 8192;
const failure = (code, message) => Object.assign(new Error(message), { name: 'InventoryError', code });
const requireValue = (test, code, message) => { if (!test) throw failure(code, message); };
const integer = value => Number.isSafeInteger(value);
const inventorySlot = slot => slot >= 36 && slot <= 44 ? slot - 36 : slot === 45 ? 40 : slot < 9 ? 44 - slot : slot;
function sameData(a, b) {
  if (Object.is(a, b)) return true;
  if (!a || !b || typeof a !== 'object' || typeof b !== 'object') return false;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  const keys = Object.keys(a);
  return keys.length === Object.keys(b).length && keys.every(key => Object.hasOwn(b, key) && sameData(a[key], b[key]));
}
const sameStack = (a, b) => a && b && a.type === b.type && a.metadata === b.metadata &&
  sameData(a.nbt, b.nbt) && sameData(a.components, b.components) && sameData(a.removedComponents, b.removedComponents);
const matches = (item, type, metadata, nbt) => item && item.type === type &&
  (metadata == null || item.metadata === metadata) && (nbt == null || sameData(item.nbt, nbt));

export function installInventory(bot, { action, snapshot, decodeItem, Item, assertActive = () => {}, isKnownActionError = () => false }) {
  const menus = new WeakMap(), closed = new WeakSet();
  let tail = Promise.resolve(), poisoned, controlFailure, order = 0, pendingSelection, nextQuickBarSlot = 0;
  const controls = new Set();
  const current = () => bot.currentWindow || bot.inventory;
  const nativeMenu = () => snapshot().hands.menu;
  function ready() { if (poisoned) throw poisoned; if (controlFailure) throw controlFailure; assertActive(); }
  function enqueue(work) {
    const ticket = ++order;
    const result = tail.then(() => { ready(); return work(ticket); });
    // A caught known operation failure does not abandon subsequent work.
    tail = result.then(() => undefined, () => undefined);
    return result;
  }
  async function send(args) {
    ready();
    try { return await action(args); }
    catch (error) { if (!isKnownActionError(error)) poisoned = error; throw error; }
  }
  function capture(window = current()) {
    const menu = menus.get(window);
    requireValue(menu, 'MissingMenuState', 'Window has no authoritative native menu state');
    return { window, id: menu.id, generation: menu.generation, clicks: 0 };
  }
  function check(ctx) {
    ready();
    const menu = nativeMenu(), associated = menus.get(ctx.window);
    requireValue(current() === ctx.window && associated?.generation === ctx.generation &&
      menu.id === ctx.id && menu.generation === ctx.generation, 'WindowChanged', 'The inventory window was replaced or closed');
    return menu;
  }
  function queueWindow(window, work) {
    // Capture now, not after preceding operations might replace the menu.
    try { const ctx = capture(window); return enqueue(ticket => { check(ctx); return work(ctx, ticket); }); }
    catch (error) { return Promise.reject(error); }
  }
  function range(window, start, end) {
    end ??= start + 1;
    requireValue(integer(start) && integer(end) && start >= 0 && end > start && end <= window.slots.length,
      'InvalidRange', 'Slot ranges must be nonempty, end-exclusive and inside the window');
    return [start, end];
  }
  function slotInfo(ctx, slot) { return check(ctx).slots.find(entry => entry.slot === slot); }
  function validSlot(ctx, slot) {
    requireValue(integer(slot) && slot >= 0 && slot < ctx.window.slots.length && slotInfo(ctx, slot),
      'InvalidSlot', `Invalid window slot ${slot}`);
  }
  async function click(ctx, slot, button = 0, mode = 0) {
    check(ctx);
    requireValue(integer(mode) && mode >= 0 && mode <= 6, 'InvalidClick', 'Invalid click mode');
    requireValue(mode < 5, 'UnsupportedClickMode', 'Drag and double-click modes are pending implementation');
    requireValue(integer(button) && (mode === 2 ? (button >= 0 && button <= 8) || button === 40
      : mode === 3 ? button === 2 : button === 0 || button === 1), 'InvalidClick', 'Invalid click button');
    if (slot === -999) requireValue(mode === 0, 'InvalidClick', 'Outside clicks use PICKUP mode');
    else validSlot(ctx, slot);
    requireValue(++ctx.clicks <= MAX_CLICKS, 'OperationLimit', 'Inventory operation exceeded its click bound');
    await send({ type: 'menu_click', menuId: ctx.id, generation: ctx.generation, slot, button, clickType: CLICK_TYPES[mode] });
    check(ctx);
  }
  function capacity(ctx, slot) {
    const menu = check(ctx), info = slotInfo(ctx, slot), item = ctx.window.slots[slot];
    if (!ctx.window.selectedItem || !info?.mayPlaceCarried || (item && info.componentMerge !== true)) return 0;
    const limit = Math.min(menu.carried.maxStackSize, info.maxStackSize);
    requireValue(integer(limit) && limit > 0, 'MissingSlotCapacity', 'Native carried/slot stack capacity is missing');
    return Math.max(0, limit - (item?.count ?? 0));
  }
  function destination(ctx, start, end, excluded = []) {
    for (const occupied of [true, false]) for (let slot = start; slot < end; slot++) {
      if (!excluded.includes(slot) && !!ctx.window.slots[slot] === occupied && capacity(ctx, slot) > 0) return slot;
    }
    return null;
  }
  async function placeCursor(ctx, slot, button = 0) {
    const before = ctx.window.selectedItem, count = before?.count ?? 0;
    const destCount = slot === -999 ? 0 : ctx.window.slots[slot]?.count ?? 0;
    await click(ctx, slot, button);
    const after = ctx.window.selectedItem, moved = count - (after?.count ?? 0);
    requireValue(moved > 0 && moved <= count && (!after || sameStack(before, after)), 'NoProgress', 'Native click did not move the carried items');
    if (slot !== -999) requireValue(sameStack(before, ctx.window.slots[slot]) &&
      ctx.window.slots[slot].count - destCount === moved, 'UnexpectedClickResult', 'Native destination changed unexpectedly');
    return moved;
  }
  async function storeCursor(ctx, start, end, preferred = null, excluded = [], preferFirst = false) {
    while (ctx.window.selectedItem) {
      const fallback = preferred != null && !excluded.includes(preferred) && capacity(ctx, preferred) > 0 ? preferred : null;
      const slot = (preferFirst ? fallback : null) ?? destination(ctx, start, end, excluded) ?? fallback;
      requireValue(slot != null, 'DestinationFull', 'destination full; carried items remain on the cursor');
      await placeCursor(ctx, slot);
      preferred = null;
    }
  }
  async function reserveCursor(ctx, excluded = []) {
    const w = ctx.window;
    if (!w.selectedItem) return;
    let space = 0;
    for (let slot = w.inventoryStart; slot < w.inventoryEnd; slot++) if (!excluded.includes(slot)) space += capacity(ctx, slot);
    requireValue(space >= w.selectedItem.count, 'OccupiedCursor', 'No safe inventory capacity for the existing cursor item');
    await storeCursor(ctx, w.inventoryStart, w.inventoryEnd, null, excluded);
  }
  async function recover(ctx, work, preferred = () => null) {
    try { return await work(); }
    catch (error) {
      if (ctx.clicks && !poisoned && (error.name === 'InventoryError' || isKnownActionError(error))) {
        try {
          check(ctx);
          await storeCursor(ctx, ctx.window.inventoryStart, ctx.window.inventoryEnd, preferred(), [], true);
        } catch (recoveryError) {
          // Preserve the original failure and the authoritative cursor. Never toss.
          error.recoveryError = recoveryError;
        }
      }
      if (poisoned) throw poisoned;
      throw error;
    }
  }
  async function pickup(ctx, slot) {
    validSlot(ctx, slot);
    const item = ctx.window.slots[slot];
    requireValue(item && slotInfo(ctx, slot).mayPickup, 'CannotPickup', `Cannot pick up slot ${slot}`);
    await click(ctx, slot);
    requireValue(sameStack(item, ctx.window.selectedItem), 'NoProgress', 'Native pickup did not supply the requested item');
  }
  async function transfer(ctx, options) {
    const w = ctx.window, { itemType, metadata, nbt } = options;
    const count = options.count ?? 1;
    requireValue(integer(itemType) && Object.hasOwn(bot.registry.items, itemType), 'InvalidItem', 'Invalid itemType');
    requireValue(integer(count) && count >= 0, 'InvalidCount', 'Transfer count must be a nonnegative safe integer');
    const [sourceStart, sourceEnd] = range(w, options.sourceStart, options.sourceEnd);
    const [destStart, destEnd] = options.destStart === -999 ? [-999, -998] : range(w, options.destStart, options.destEnd);
    requireValue(destStart === -999 || sourceEnd <= destStart || destEnd <= sourceStart, 'OverlappingRanges', 'Source and destination ranges must not overlap');
    if (count === 0) return;
    let remaining = count, sourceSlot = null;
    await recover(ctx, async () => {
      if (w.selectedItem && !matches(w.selectedItem, itemType, metadata, nbt)) await reserveCursor(ctx);
      while (remaining > 0) {
        check(ctx);
        if (!w.selectedItem) {
          sourceSlot = null;
          for (let slot = sourceStart; slot < sourceEnd; slot++) if (matches(w.slots[slot], itemType, metadata, nbt)) {
            sourceSlot = slot; break;
          }
          requireValue(sourceSlot != null, 'InsufficientItems', `Can't find item ${itemType} in slots [${sourceStart} - ${sourceEnd}]`);
          await pickup(ctx, sourceSlot);
        }
        requireValue(matches(w.selectedItem, itemType, metadata, nbt), 'UnexpectedClickResult', 'Carried item changed during transfer');
        const slot = destStart === -999 ? -999 : destination(ctx, destStart, destEnd);
        requireValue(slot != null, 'DestinationFull', 'destination full');
        const amount = Math.min(w.selectedItem.count, slot === -999 ? w.selectedItem.count : capacity(ctx, slot));
        const moved = await placeCursor(ctx, slot, amount <= remaining ? 0 : 1);
        requireValue(moved <= remaining, 'UnexpectedClickResult', 'Native click moved more than the requested count');
        remaining -= moved;
      }
      await storeCursor(ctx, sourceStart, sourceEnd, sourceSlot);
    }, () => sourceSlot);
  }
  async function move(ctx, source, dest) {
    validSlot(ctx, source); validSlot(ctx, dest);
    if (source === dest || !ctx.window.slots[source]) return;
    await recover(ctx, async () => {
      await reserveCursor(ctx, [source, dest]);
      const item = ctx.window.slots[source], before = ctx.window.slots[dest], count = before?.count ?? 0;
      await pickup(ctx, source);
      await click(ctx, dest);
      requireValue(sameStack(item, ctx.window.slots[dest]) && (!sameStack(item, before) || ctx.window.slots[dest].count > count),
        'NoProgress', 'Native destination did not accept the item');
      if (ctx.window.selectedItem) await storeCursor(ctx, source, source + 1, source);
    }, () => source);
  }
  async function close(ctx) {
    check(ctx);
    await send({ type: 'menu_close', menuId: ctx.id, generation: ctx.generation });
    ready();
    requireValue(!bot.currentWindow, 'WindowStillOpen', 'Native close did not return to inventory');
  }
  function mappedSlot(ctx, slot) {
    const native = inventorySlot(slot);
    const entry = check(ctx).slots.find(entry => entry.inventorySlot === native);
    requireValue(entry, 'UnmappedEquipmentSlot', `Equipment inventory slot ${slot} is not exposed by this menu`);
    return entry.slot;
  }
  function sourceSlot(ctx, item) {
    if (ctx.window.slots[item.slot] === item) return item.slot;
    requireValue(bot.inventory.slots[item.slot] === item, 'ItemChanged', 'Item is not in the observed inventory/window');
    return mappedSlot(ctx, item.slot);
  }
  async function select(slot, ticket) {
    if (!pendingSelection || pendingSelection.ticket <= ticket) {
      pendingSelection = { slot, ticket };
      bot.quickBarSlot = slot;
    }
    try { if (snapshot().hands.selected !== slot) await send({ type: 'select_hotbar', slot }); }
    finally {
      if (pendingSelection?.ticket === ticket) {
        pendingSelection = undefined;
        bot.quickBarSlot = snapshot().hands.selected;
      }
    }
  }
  const equipmentSlot = destination => {
    const slot = destination === 'hand' ? 36 + bot.quickBarSlot : { head: 5, torso: 6, legs: 7, feet: 8, 'off-hand': 45 }[destination];
    requireValue(slot != null, 'InvalidEquipment', `invalid destination: ${destination}`);
    return slot;
  };
  bot.setQuickBarSlot = slot => {
    requireValue(integer(slot) && slot >= 0 && slot < 9, 'InvalidHotbarSlot', 'Hotbar slot must be an integer from 0 to 8');
    ready();
    if (bot.quickBarSlot === slot) return;
    const ticket = order + 1;
    pendingSelection = { slot, ticket }; bot.quickBarSlot = slot;
    const pending = enqueue(order => select(slot, order));
    controls.add(pending);
    pending.then(() => controls.delete(pending), error => {
      controlFailure = error; controls.delete(pending);
      if (pendingSelection?.ticket === ticket) { pendingSelection = undefined; bot.quickBarSlot = snapshot().hands.selected; }
    });
  };
  bot.clickWindow = (slot, button, mode) => queueWindow(current(), ctx => click(ctx, slot, button, mode));
  bot.moveSlotItem = (source, dest) => queueWindow(current(), ctx => move(ctx, source, dest));
  bot.toss = (itemType, metadata, count) => queueWindow(current(), ctx => transfer(ctx, {
    itemType, metadata, count, sourceStart: ctx.window.inventoryStart, sourceEnd: ctx.window.inventoryEnd, destStart: -999,
  }));
  bot.tossStack = item => queueWindow(current(), async ctx => {
    requireValue(item, 'InvalidItem', 'tossStack requires an Item');
    const source = sourceSlot(ctx, item);
    await recover(ctx, async () => {
      await reserveCursor(ctx, [source]); await pickup(ctx, source); await placeCursor(ctx, -999);
    }, () => source);
    await close(ctx);
  });
  bot.equip = (item, destination = 'hand') => queueWindow(current(), async (ctx, ticket) => {
    destination ??= 'hand';
    if (typeof item === 'number') item = bot.inventory.findInventoryItem(item);
    requireValue(item && typeof item === 'object', 'InvalidItem', 'Invalid item object in equip (item is null or typeof item is not object)');
    equipmentSlot(destination); // Validate before moving a cursor or closing a menu.
    const playerItem = bot.inventory.slots[item.slot] === item;
    if (playerItem && destination !== 'hand' && item.slot === equipmentSlot(destination)) return;
    const armor = ['head', 'torso', 'legs', 'feet'].includes(destination);
    const sourceHidden = playerItem && !check(ctx).slots.some(entry => entry.inventorySlot === inventorySlot(item.slot));
    if (bot.currentWindow && (armor || sourceHidden)) {
      if (!playerItem) {
        const source = sourceSlot(ctx, item);
        await transfer(ctx, { itemType: item.type, metadata: item.metadata, nbt: item.nbt, count: item.count,
          sourceStart: source, sourceEnd: source + 1, destStart: ctx.window.inventoryStart, destEnd: ctx.window.inventoryEnd });
        item = bot.inventory.slots.slice(9, 45).find(candidate => sameStack(item, candidate));
        requireValue(item, 'ItemChanged', 'Transferred equipment is missing from the player inventory');
      }
      const slot = item.slot;
      await reserveCursor(ctx); await close(ctx);
      ctx = capture(bot.inventory); check(ctx); item = bot.inventory.slots[slot];
      requireValue(item, 'ItemChanged', 'Equipment changed while closing the container');
    }
    const source = sourceSlot(ctx, item);
    if (destination === 'hand') {
      const nativeSource = slotInfo(ctx, source).inventorySlot;
      if (integer(nativeSource) && nativeSource >= 0 && nativeSource < 9) { await select(nativeSource, ticket); return; }
      let dest = bot.inventory.firstEmptyHotbarSlot();
      if (dest == null) { dest = 36 + nextQuickBarSlot; nextQuickBarSlot = (nextQuickBarSlot + 1) % 9; }
      const target = mappedSlot(ctx, dest);
      await select(dest - 36, ticket); await move(ctx, source, target);
    } else if (destination === 'off-hand' && !check(ctx).slots.some(entry => entry.inventorySlot === 40)) {
      await reserveCursor(ctx, [source]);
      await click(ctx, source, 40, 2);
      requireValue(sameStack(item, bot.inventory.slots[45]), 'NoProgress', 'Native offhand swap did not equip the item');
    } else await move(ctx, source, mappedSlot(ctx, equipmentSlot(destination)));
  });
  // Quick-move one occupied non-result slot out, keeping the cursor recoverable.
  async function putAway(ctx, slot) {
    validSlot(ctx, slot);
    const w = ctx.window;
    if (!w.slots[slot]) return;
    await recover(ctx, async () => {
      await reserveCursor(ctx, [slot]);
      const before = w.slots[slot], count = before.count;
      await click(ctx, slot, 0, 1);
      requireValue(!w.slots[slot] || !sameStack(before, w.slots[slot]) || w.slots[slot].count < count,
        'NoProgress', 'Native quick-move made no progress');
    }, () => slot);
  }
  bot.unequip = destination => queueWindow(current(), async (ctx, ticket) => {
    equipmentSlot(destination);
    if (destination !== 'hand' && !bot.inventory.slots[equipmentSlot(destination)]) return;
    if (bot.currentWindow && ['head', 'torso', 'legs', 'feet'].includes(destination)) {
      await reserveCursor(ctx); await close(ctx); ctx = capture(bot.inventory); check(ctx);
    }
    if (destination === 'hand') {
      const empty = bot.inventory.firstEmptyHotbarSlot();
      if (empty != null) { await select(empty - 36, ticket); return; }
      const source = mappedSlot(ctx, 36 + snapshot().hands.selected);
      const dest = bot.inventory.firstEmptyInventorySlot();
      // Correct upstream's implicit toss: only explicit drop APIs may discard.
      requireValue(dest != null, 'DestinationFull', 'No inventory room to unequip hand');
      await move(ctx, source, mappedSlot(ctx, dest));
    } else if (destination === 'off-hand' && !check(ctx).slots.some(entry => entry.inventorySlot === 40)) {
      if (!bot.inventory.slots[45]) return;
      const dest = bot.inventory.firstEmptyInventorySlot();
      requireValue(dest != null, 'DestinationFull', 'No inventory room to unequip offhand');
      const slot = mappedSlot(ctx, dest); await reserveCursor(ctx, [slot]); await click(ctx, slot, 40, 2);
      requireValue(!bot.inventory.slots[45], 'NoProgress', 'Native offhand swap did not clear offhand');
    } else await putAway(ctx, mappedSlot(ctx, equipmentSlot(destination)));
  });
  function vector(value, label) {
    requireValue(value && ['x', 'y', 'z'].every(key => Number.isFinite(value[key])), 'InvalidVector', `Invalid ${label}`);
    return { x: value.x, y: value.y, z: value.z };
  }
  function openRequest(target, direction, cursorPos, entity) {
    if (entity) {
      requireValue(typeof target?.uuid === 'string' && target.uuid.length > 0, 'InvalidEntity', 'Entity has no native UUID');
      return { type: 'interact', entity: target.uuid };
    }
    const position = vector(target?.position, 'block position');
    requireValue(Object.values(position).every(integer), 'InvalidBlock', 'Block position must be integral');
    const d = vector(direction ?? new Vec3(0, 1, 0), 'block direction');
    const face = new Map([['0,1,0', 'up'], ['0,-1,0', 'down'], ['0,0,-1', 'north'], ['0,0,1', 'south'], ['1,0,0', 'east'], ['-1,0,0', 'west']]).get(`${d.x},${d.y},${d.z}`);
    requireValue(face, 'InvalidDirection', 'Block direction must identify one cardinal face');
    const cursor = vector(cursorPos ?? new Vec3(.5, .5, .5), 'block cursor');
    requireValue(Object.values(cursor).every(value => value >= 0 && value <= 1), 'InvalidCursor', 'Block cursor must be inside [0,1]');
    return { type: 'interact', position, face, cursorPos: cursor };
  }
  function open(target, direction, cursorPos, entity, storage) {
    let request;
    try { request = openRequest(target, direction, cursorPos, entity); }
    catch (error) { return Promise.reject(error); }
    return queueWindow(current(), async ctx => {
      await reserveCursor(ctx);
      await send(request);
      const window = bot.currentWindow, menu = nativeMenu();
      requireValue(window && menus.get(window)?.generation === menu.generation && menu.generation !== ctx.generation,
        'NoWindowOpened', 'Interaction did not open a new authoritative window');
      if (storage) requireValue(STORAGE.some(type => window.type.startsWith(`minecraft:${type}`)),
        'NotContainer', `Non-container window used as a container: ${window.type}`);
      return window;
    });
  }
  bot.openBlock = (block, direction, cursorPos) => open(block, direction, cursorPos, false, false);
  bot.openEntity = entity => open(entity, null, null, true, false);
  bot.openContainer = (target, direction, cursorPos) => {
    const kind = target?.constructor?.name;
    if (kind !== 'Entity' && !(kind === 'Block' && STORAGE.includes(target.name)))
      return Promise.reject(failure('NotContainer', 'containerToOpen is neither a block nor an entity'));
    return open(target, direction, cursorPos, kind === 'Entity', true);
  };

  function craftingGrid(ctx) {
    const slots = check(ctx).slots;
    const inputs = slots.filter(slot => slot.role === 'crafting_input');
    const result = slots.filter(slot => slot.role === 'crafting_result');
    const width = inputs[0]?.craftingWidth, height = inputs[0]?.craftingHeight;
    requireValue(integer(width) && integer(height) && width >= 1 && width <= 3 && height >= 1 && height <= 3 &&
      inputs.length === width * height && result.length === 1, 'MissingCraftingGrid', 'Native crafting grid metadata is missing');
    const ordered = new Array(inputs.length);
    for (const input of inputs) {
      requireValue(input.craftingWidth === width && input.craftingHeight === height && integer(input.craftingIndex) &&
        input.craftingIndex >= 0 && input.craftingIndex < inputs.length && !ordered[input.craftingIndex],
      'InvalidCraftingGrid', 'Native crafting grid indices are inconsistent');
      ordered[input.craftingIndex] = input;
    }
    return { width, height, inputs: ordered.map(input => input.slot), result: result[0].slot };
  }
  function craftingPlan(recipe, width, height) {
    const cells = new Array(width * height).fill(null);
    const ingredient = value => {
      if (value == null || value.id === -1) return null;
      requireValue(integer(value.id) && Object.hasOwn(bot.registry.items, value.id),
        'InvalidRecipe', 'Recipe contains an invalid ingredient');
      return { id: value.id, metadata: value.metadata };
    };
    if (recipe.inShape) {
      requireValue(Array.isArray(recipe.inShape) && recipe.inShape.length <= height, 'InvalidRecipe', 'Recipe is taller than the crafting grid');
      recipe.inShape.forEach((row, y) => {
        requireValue(Array.isArray(row) && row.length <= width, 'InvalidRecipe', 'Recipe is wider than the crafting grid');
        row.forEach((item, x) => { cells[x + width * y] = ingredient(item); });
      });
    }
    const unused = cells.flatMap((item, index) => item ? [] : [index]);
    if (recipe.ingredients) {
      requireValue(Array.isArray(recipe.ingredients) && recipe.ingredients.length <= unused.length,
        'InvalidRecipe', 'Recipe has too many ingredients for the crafting grid');
      // Pinned shapeless placement consumes the last available grid slot first.
      for (const item of recipe.ingredients) cells[unused.pop()] = ingredient(item);
    }
    requireValue(cells.some(Boolean), 'InvalidRecipe', 'Recipe has no ingredients');
    return cells;
  }
  async function clearCraftingGrid(ctx, grid) {
    await reserveCursor(ctx);
    for (const slot of grid.inputs) {
      if (!ctx.window.slots[slot]) continue;
      await recover(ctx, async () => {
        await pickup(ctx, slot);
        await storeCursor(ctx, ctx.window.inventoryStart, ctx.window.inventoryEnd);
      }, () => slot);
    }
  }
  bot.craft = (recipe, count, craftingTable) => {
    let operations, request;
    try {
      requireValue(recipe && typeof recipe === 'object', 'InvalidRecipe', 'craft requires a Recipe');
      operations = parseInt(count ?? 1, 10);
      requireValue(!recipe.requiresTable || craftingTable, 'RequiresCraftingTable', 'Recipe requires craftingTable, but one was not supplied');
      // Nonpositive/NaN counts retain pinned's zero-iteration behavior.
      if (!(operations > 0)) return Promise.resolve();
      requireValue(integer(operations) && operations <= 256, 'OperationLimit', 'Craft is limited to 256 operations per call');
      requireValue(integer(recipe.result?.id) && Object.hasOwn(bot.registry.items, recipe.result.id),
        'InvalidRecipe', 'Recipe result has an invalid item id');
      craftingPlan(recipe, craftingTable ? 3 : 2, craftingTable ? 3 : 2);
      if (craftingTable) request = openRequest(craftingTable, null, null, false);
    } catch (error) { return Promise.reject(error); }
    return queueWindow(current(), async ctx => {
      let grid, opened = false, completedCrafts = 0;
      try {
        await reserveCursor(ctx);
        if (bot.currentWindow) {
          if (check(ctx).slots.some(slot => slot.role === 'crafting_input')) await clearCraftingGrid(ctx, craftingGrid(ctx));
          await close(ctx); ctx = capture(bot.inventory); check(ctx);
        }
        if (request) {
          await send(request);
          const window = bot.currentWindow;
          requireValue(window && nativeMenu().generation !== ctx.generation,
            'NoWindowOpened', 'Interaction did not open a crafting table');
          ctx = capture(window); check(ctx);
          requireValue(window.type.startsWith('minecraft:crafting'), 'NotCraftingTable', 'Non-crafting window used as a crafting table');
          opened = true;
        }
        grid = craftingGrid(ctx);
        const plan = craftingPlan(recipe, grid.width, grid.height);
        for (let operation = 0; operation < operations; operation++) {
          await clearCraftingGrid(ctx, grid);
          for (let index = 0; index < plan.length; index++) {
            const item = plan[index];
            if (!item) continue;
            await transfer(ctx, { itemType: item.id, metadata: item.metadata, count: 1,
              sourceStart: ctx.window.inventoryStart, sourceEnd: ctx.window.inventoryEnd,
              destStart: grid.inputs[index], destEnd: grid.inputs[index] + 1 });
          }
          check(ctx);
          const result = ctx.window.slots[grid.result];
          requireValue(matches(result, recipe.result.id, recipe.result.metadata),
            'CraftResultUnavailable', 'Native crafting result is empty or does not match the requested recipe');
          // ResultSlot.onTake owns consumption, components and all remainders.
          // Never QUICK_MOVE: it can perform more than one recipe operation.
          await pickup(ctx, grid.result);
          requireValue(ctx.window.selectedItem.count === result.count,
            'UnexpectedClickResult', 'Native crafting result count changed during pickup');
          completedCrafts++;
          await storeCursor(ctx, ctx.window.inventoryStart, ctx.window.inventoryEnd);
          await clearCraftingGrid(ctx, grid);
        }
        if (opened) await close(ctx);
      } catch (error) {
        error.completedCrafts = completedCrafts;
        if (grid && !poisoned && (error.name === 'InventoryError' || isKnownActionError(error))) {
          try {
            check(ctx);
            await clearCraftingGrid(ctx, grid);
            // Failed recovery leaves the real cursor/grid visible, never closes
            // the menu to provoke native overflow drops.
            if (opened) await close(ctx);
          } catch (recoveryError) { error.recoveryError = recoveryError; }
        }
        if (poisoned) throw poisoned;
        throw error;
      }
    });
  };
  function windowClosed(window) {
    if (window && !closed.has(window)) { closed.add(window); emitWindow(window, 'close'); }
  }
  // Shared internal operations stay unqueued; public adapters own one queue turn.
  const io = {
    queueWindow, check, send, pickup, storeCursor, reserveCursor, transfer, move, sourceSlot, recover,
    requireValue, sameStack, isKnownActionError, decodeItem, assertReady: ready,
    capture, current, close, select, snapshot, click,
  };
  const specialized = installSpecializedWindows(bot, io);
  installBooks(bot, io);
  installCreativeInventory(bot, io, Item);
  function syncWindow(window, menu) {
    requireValue(integer(menu.id) && integer(menu.generation), 'MissingMenuGeneration', 'Native menu id/generation is required');
    const decorated = menus.has(window);
    menus.set(window, { id: menu.id, generation: menu.generation });
    if (!decorated) {
      window.close = () => queueWindow(window, close);
      window.deposit = (itemType, metadata, count, nbt) => queueWindow(window, ctx => transfer(ctx, { itemType, metadata, count, nbt,
        sourceStart: window.inventoryStart, sourceEnd: window.inventoryEnd, destStart: 0, destEnd: window.inventoryStart }));
      window.withdraw = (itemType, metadata, count, nbt) => queueWindow(window, ctx => transfer(ctx, { itemType, metadata, count, nbt,
        sourceStart: 0, sourceEnd: window.inventoryStart, destStart: window.inventoryStart, destEnd: window.inventoryEnd }));
    }
    specialized.syncWindow(window, menu);
  }
  // Synchronous public controls share the cursor queue so an attack/use cannot
  // overtake an earlier equip, or be overtaken by the next inventory operation.
  function enqueueControl(args) {
    ready();
    requireValue(controls.size < 256, 'ControlLimit', 'Too many pending synchronous controls');
    const pending = enqueue(async () => {
      const result = await send(args);
      requireValue(result.result?.status !== 'failed', 'NativeInteractionFailed', 'Native interaction explicitly failed');
    });
    controls.add(pending);
    pending.then(() => controls.delete(pending), error => { controlFailure = error; controls.delete(pending); });
  }
  return { syncWindow, windowClosed, enqueueControl, selection: () => pendingSelection?.slot,
    async drainControls() {
      while (controls.size) await Promise.allSettled([...controls]);
      if (controlFailure) throw controlFailure;
      if (poisoned) throw poisoned;
    },
  };
}
