// Adapted from Mineflayer 4.39.0 entities.js (MIT); see entities.LICENSE.
export function installEntityQueries(bot) {
  bot.findPlayer = bot.findPlayers = filter => {
    const result = Object.values(bot.entities).filter(entity => {
      if (entity.type !== 'player') return false;
      if (filter === null) return true;
      if (filter instanceof RegExp) return entity.username.search(filter) !== -1;
      if (typeof filter === 'function') return filter(entity);
      if (typeof filter === 'string') return entity.username.toLowerCase() === filter.toLowerCase();
      return false;
    });
    if (typeof filter === 'string') return result.length === 0 ? null : result.length === 1 ? result[0] : result;
    return result;
  };
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
