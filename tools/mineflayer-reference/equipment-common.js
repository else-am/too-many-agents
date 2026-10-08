// Ordinary inventory equipment; the separate container defect is not hidden.
if (bot.currentWindow || bot.inventory.selectedItem || bot.quickBarSlot !== 6)
  throw new Error('Equipment fixture window/cursor/selection differs');
const find = name => bot.inventory.items().find(item => item.name === name);
const helmet = find('iron_helmet'), shield = find('shield'), sword = find('diamond_sword');
if (!helmet || !shield || !sword || sword.durabilityUsed !== 7 ||
    bot.inventory.count(bot.registry.itemsByName.stone.id) !== 17)
  throw new Error('Equipment fixture items differ');
const swordComponents = JSON.stringify(sword.components);
const counts = () => Object.fromEntries(['stone','iron_helmet','shield','diamond_sword','diamond_pickaxe']
  .map(name => [name, bot.inventory.count(bot.registry.itemsByName[name].id)]));
const before = counts();
const voidResult = value => { if (value !== undefined) throw new Error('Equipment result must be void'); };
voidResult(await bot.equip(helmet, 'head'));
if (bot.inventory.slots[5]?.name !== 'iron_helmet') throw new Error('Head equip not hydrated');
voidResult(await bot.equip(shield.type, 'off-hand'));
if (bot.inventory.slots[45]?.name !== 'shield') throw new Error('Offhand equip not hydrated');
voidResult(await bot.unequip('head'));
voidResult(await bot.unequip('off-hand'));
if (bot.inventory.slots[5] || bot.inventory.slots[45]) throw new Error('Unequip not hydrated');
voidResult(await bot.equip(sword.type, 'hand'));
if (bot.heldItem?.name !== 'diamond_sword' || bot.heldItem.durabilityUsed !== 7 ||
    JSON.stringify(bot.heldItem.components) !== swordComponents)
  throw new Error('Sword identity/components differ');
voidResult(await bot.unequip('hand'));
if (bot.heldItem) throw new Error('Hand was not emptied');
bot.setQuickBarSlot(6);
await bot.waitForTicks(1);
if (bot.heldItem?.name !== 'diamond_pickaxe' || bot.quickBarSlot !== 6 ||
    bot.currentWindow || bot.inventory.selectedItem || JSON.stringify(counts()) !== JSON.stringify(before))
  throw new Error('Final equipment/inventory differs');
return {counts:counts(),head:null,offhand:null,selected:bot.quickBarSlot,held:bot.heldItem.name,
  swordDamage:find('diamond_sword').durabilityUsed,swordComponentsPreserved:true,voidResults:true};
