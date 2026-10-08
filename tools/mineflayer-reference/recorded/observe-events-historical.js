// Harness starts with diamond ore at (3,-60,2), then replaces it with stone
// after this script is waiting. The script must observe it without polling.
const entity = bot.entity;
const position = entity.position;
const target = new Vec3(3, -60, 2);
let ticks = 0;
bot.on('physicsTick', () => { ticks++; });
const change = await new Promise(resolve => {
  bot.on('blockUpdate', (before, after) => {
    if (after?.position.equals(target) && after.name === 'stone') {
      resolve({ before: before?.name, after: after.name, readableDuringEvent: bot.blockAt(target).name });
    }
  });
});
await bot.waitForTicks(2);
return { change, ticks, sameEntity: bot.entity === entity, samePosition: bot.entity.position === position };