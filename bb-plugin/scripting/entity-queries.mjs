// Adapted from Mineflayer 4.39.0 entities.js (MIT); see entities.LICENSE.
export function installEntityQueries(bot) {
  bot.nearestEntity = (match = () => true) => {
    let closest = null, distance = Number.MAX_VALUE;
    for (const entity of Object.values(bot.entities)) {
      if (entity === bot.entity || !match(entity)) continue;
      const candidate = bot.entity.position.distanceSquared(entity.position);
      if (candidate < distance) { closest = entity; distance = candidate; }
    }
    return closest;
  };
}
