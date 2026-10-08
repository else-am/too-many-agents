// Leaves the opposite dominant hand selected for independent native NBT checks.
// The coordinator restores originalHand afterward with a separate drained call.
const settings = bot.settings, originalHand = settings.mainHand;
if (!['left', 'right'].includes(originalHand) || bot.currentWindow || bot.inventory.selectedItem)
  throw new Error('Settings prerequisites differ');
const inventory = JSON.stringify(bot.inventory.slots);
const targetHand = originalHand === 'right' ? 'left' : 'right';
const rejected = [];
for (const options of [{ mainHand: targetHand, viewDistance: 8 }, { mainHand: 'invalid' }, null]) {
  let error;
  try { bot.setSettings(options); } catch (caught) { error = caught; }
  if (!error) throw new Error('Invalid settings accepted');
  rejected.push({ code: error.code, message: error.message });
}
if (bot.setSettings({}) !== undefined) throw new Error('Empty settings return differs');
await bot.waitForTicks(1);
if (settings.mainHand !== originalHand) throw new Error('Rejected settings changed the body');
if (bot.setSettings({ mainHand: targetHand }) !== undefined) throw new Error('Settings return differs');
await bot.waitForTicks(1);
if (bot.settings !== settings || settings.mainHand !== targetHand)
  throw new Error('Authoritative dominant hand missing');
bot.swingArm('right');
await bot.waitForTicks(1);
if (JSON.stringify(bot.inventory.slots) !== inventory || bot.settings !== settings)
  throw new Error('Hand change altered inventory or settings identity');
return { originalHand, targetHand, rejected, bodyUuid: bot.entity.uuid,
  inventoryUnchanged: true, mainHand: settings.mainHand };
