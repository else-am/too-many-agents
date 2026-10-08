// Guarded native fixture: creative body at (32.5,-60,0.5), clear stone floor,
// solid wall x=35, y=-60..-56, z=-2..3; empty slot 9, no menu/cursor.
if (bot.game.gameMode !== 'creative' || bot.inventory.slots[9] || bot.currentWindow || bot.inventory.selectedItem)
  throw new Error('Creative fixture prerequisites differ');
const before = JSON.stringify(bot.inventory.slots);
const origin = bot.entity.position.clone();
if (origin.distanceTo(new Vec3(32.5, -60, 0.5)) > 0.2 || !bot.entity.onGround)
  throw new Error('Creative flight origin differs');
const sword = Item.fromNotch({
  itemId: bot.registry.itemsByName.diamond_sword.id, itemCount: 1,
  components: [
    { type: 'damage', data: 7 },
    { type: 'custom_name', data: { type: 'string', value: 'Creative fixture' } },
  ], removeComponents: [],
});
await bot.creative.setInventorySlot(9, sword);
const observed = bot.inventory.slots[9];
if (!(observed instanceof Item) || observed.name !== 'diamond_sword' || observed.durabilityUsed !== 7
  || !observed.components.some(c => c.type === 'custom_name' && c.data.value === 'Creative fixture'))
  throw new Error('Authoritative creative item/component write differs');
await bot.creative.clearSlot(9);
if (JSON.stringify(bot.inventory.slots) !== before) throw new Error('Creative clear changed unrelated inventory');
if (bot.creative.startFlying() !== undefined) throw new Error('startFlying return differs');
await bot.waitForTicks(1);
const target = origin.offset(2, 2, 0);
await bot.creative.flyTo(target);
const arrived = bot.entity.position.clone();
if (arrived.distanceTo(target) > 0.15) throw new Error('Native flight did not reach target');
await bot.waitForTicks(5);
if (bot.entity.position.distanceTo(arrived) > 0.15) throw new Error('Creative hover drifted');
let obstruction;
try { await bot.creative.flyTo(origin.offset(4, 2, 0)); }
catch (error) { obstruction = error.message; }
if (!obstruction || bot.entity.position.x >= 35) throw new Error('Flight crossed the solid fixture wall');
await bot.creative.flyTo(origin);
if (bot.creative.stopFlying() !== undefined) throw new Error('stopFlying return differs');
await bot.waitForTicks(3);
if (!bot.entity.onGround || JSON.stringify(bot.inventory.slots) !== before)
  throw new Error('Creative stop/return/inventory differs');
return { item: { name: observed.name, damage: observed.durabilityUsed, components: observed.components },
  arrived, obstruction, position: bot.entity.position.clone(), onGround: bot.entity.onGround, inventoryUnchanged: true };
