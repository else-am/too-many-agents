// PREPARED ONLY. Prepend to the unchanged placement-refusal.js procedure.
const expectedBodyUuid = 'REPLACE_WITH_NEW_DISPOSABLE_BODY_UUID';
const expectedObstructionUuid = 'REPLACE_WITH_NEW_OBSTRUCTION_UUID';
const testedBody = bot.entity;
if (testedBody.uuid !== expectedBodyUuid || !testedBody.alive || !testedBody.onGround ||
    testedBody.isSleeping || bot.vehicle || bot.game.gameMode !== 'survival' ||
    testedBody.position.distanceTo(new Vec3(48.5,-60,5.5)) > .05 ||
    bot.currentWindow || bot.inventory.selectedItem || Object.values(bot.controlState).some(Boolean))
  throw new Error('Native refusal body fixture differs');
await bot.waitForChunksToLoad();
const obstruction = Object.values(bot.entities).find(entity => entity.uuid === expectedObstructionUuid);
if (!obstruction || obstruction.name !== 'cow' || !obstruction.alive ||
    obstruction.position.distanceTo(new Vec3(48.5,-60,8.5)) > .05 ||
    !(obstruction.width > 0 && obstruction.height > 0))
  throw new Error('Native refusal obstruction differs');
const occupied = bot.inventory.slots.filter(Boolean);
if (occupied.length !== 1 || occupied[0] !== bot.heldItem || bot.heldItem.name !== 'stone' || bot.heldItem.count !== 1)
  throw new Error('Native refusal disposable inventory differs');
