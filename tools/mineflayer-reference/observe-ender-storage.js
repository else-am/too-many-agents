// Coordinator changes phase and supplies the saved deposit receipt on reopen.
// Inspect/correct the coordinate and empty native ender slot before deposit.
const phase = 'deposit';
const chestAt = new Vec3(112, -60, 64), enderSlot = 26;
const receipt = null;
const fixtureName = 'Ender retention fixture', fixtureLore = 'Preserved through restart';
const chest = bot.blockAt(chestAt), start = Date.now(), selection = bot.quickBarSlot;
if (!['deposit', 'withdraw'].includes(phase) || chest?.name !== 'ender_chest' ||
    bot.currentWindow || bot.inventory.selectedItem)
  throw new Error('Ender storage prerequisites differ');
const itemData = item => item ? { name: item.name, count: item.count, components: item.components } : null;
const inventory = () => bot.inventory.slots.map(itemData);
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const named = item => item?.name === 'paper' &&
  ChatMessage.fromNotch(item.customName)?.toString() === fixtureName;
const checkFixture = item => {
  if (!(item instanceof Item) || !named(item) || item.count !== 1 ||
      !Array.isArray(item.customLore) || item.customLore.length !== 1 ||
      ChatMessage.fromNotch(item.customLore[0])?.toString() !== fixtureLore)
    throw new Error('Named paper count/components differ');
};
const originalInventory = inventory();
const fixtureSlots = bot.inventory.slots.flatMap((item, slot) => named(item) ? [slot] : []);
let inventorySlot;
if (phase === 'deposit') {
  if (fixtureSlots.length !== 1 || fixtureSlots[0] < 9 || fixtureSlots[0] > 44)
    throw new Error('One storage fixture paper required');
  inventorySlot = fixtureSlots[0];
  checkFixture(bot.inventory.slots[inventorySlot]);
} else {
  if (!receipt || receipt.enderSlot !== enderSlot || fixtureSlots.length ||
      !same(originalInventory, receipt.otherInventory) || bot.quickBarSlot !== receipt.selection)
    throw new Error('Reopened ordinary inventory/receipt differs');
  inventorySlot = receipt.inventorySlot;
  if (!Number.isInteger(inventorySlot) || inventorySlot < 9 || inventorySlot > 44 ||
      bot.inventory.slots[inventorySlot]) throw new Error('Original paper slot unavailable');
}
const window = await bot.openContainer(chest);
if (window !== bot.currentWindow || window.inventoryStart !== 27 ||
    !Number.isInteger(enderSlot) || enderSlot < 0 || enderSlot >= window.inventoryStart || window.selectedItem)
  throw new Error('Native ender window mapping differs');
const menuSlot = inventorySlot >= 36 ? window.hotbarStart + inventorySlot - 36
  : window.inventoryStart + inventorySlot - 9;
const ender = () => window.slots.slice(0, window.inventoryStart).map(itemData);
let result;
if (phase === 'deposit') {
  const otherEnder = ender(), fixture = itemData(bot.inventory.slots[inventorySlot]);
  if (window.slots[enderSlot] || window.slots.slice(0, window.inventoryStart).some(named) ||
      !same(itemData(window.slots[menuSlot]), fixture))
    throw new Error('Native deposit slots differ or would overwrite storage');
  await bot.clickWindow(menuSlot, 0, 0);
  if (window.slots[menuSlot] || !same(itemData(window.selectedItem), fixture))
    throw new Error('Deposit pickup differs');
  await bot.clickWindow(enderSlot, 0, 0);
  const expectedEnder = otherEnder.slice(); expectedEnder[enderSlot] = fixture;
  if (window.selectedItem || !same(ender(), expectedEnder)) throw new Error('Native deposit differs');
  await window.close();
  const otherInventory = originalInventory.slice(); otherInventory[inventorySlot] = null;
  if (!same(inventory(), otherInventory)) throw new Error('Deposit changed unrelated inventory');
  result = { enderSlot, inventorySlot, fixture, otherEnder, otherInventory, originalInventory, selection };
} else {
  checkFixture(window.slots[enderSlot]);
  const expectedEnder = receipt.otherEnder.slice(); expectedEnder[enderSlot] = receipt.fixture;
  if (!same(ender(), expectedEnder) || window.slots[menuSlot])
    throw new Error('Reopened native ender contents/components differ');
  await bot.clickWindow(enderSlot, 0, 0);
  if (window.slots[enderSlot] || !same(itemData(window.selectedItem), receipt.fixture))
    throw new Error('Withdrawal pickup differs');
  await bot.clickWindow(menuSlot, 0, 0);
  if (window.selectedItem || !same(ender(), receipt.otherEnder))
    throw new Error('Withdrawal changed unrelated ender contents');
  await window.close();
  if (!same(inventory(), receipt.originalInventory))
    throw new Error('Withdrawal duplicated/lost items or changed original slots');
  result = { enderSlot, inventorySlot, fixture: itemData(bot.inventory.slots[inventorySlot]),
    originalEnderRestored: true, originalInventoryRestored: true };
}
if (bot.currentWindow || bot.inventory.selectedItem || bot.quickBarSlot !== selection)
  throw new Error('Ender storage close/cursor/selection differs');
return { start, end: Date.now(), phase, chestAt, result, cursorEmpty: true };
