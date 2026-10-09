// Agents often reach for full Mineflayer from memory. Reading a member this
// subset lacks throws with a pointer to what to use instead of yielding undefined.
const plugins = 'Mineflayer plugins are not supported in this subset.';
const hints = {
  pathfinder: 'Pathfinder is not part of this subset: your body uses its own native navigation. Use await bot.moveTo(position) and bot.stopMoving().',
  navigate: 'Use await bot.moveTo(position); your body navigates natively.',
  loadPlugin: plugins, loadPlugins: plugins, hasPlugin: plugins,
  collectBlock: `${plugins} Use findBlocks, moveTo near the block, then dig.`,
  pvp: `${plugins} Use bot.attack(entity).`,
  tool: `${plugins} Pick a tool from bot.inventory.items() and bot.equip(item, 'hand').`,
  autoEat: plugins, armorManager: plugins,
  health: 'Use bot.entity.health.',
  oxygenLevel: 'Use bot.entity.airSupply.',
  food: 'Hunger is not observed in this subset.',
  foodSaturation: 'Hunger is not observed in this subset.',
  player: 'Use bot.entity for your body.',
  world: 'Use bot.blockAt(position) and bot.findBlocks(options).',
  physics: 'There is no local physics simulation; use bot.moveTo or setControlState.',
  physicsEnabled: 'There is no local physics simulation; use bot.moveTo or setControlState.',
  blockAtEntityCursor: 'Use bot.blockAtCursor(maxDistance, matcher, entity).',
  transfer: 'Use window.withdraw / window.deposit, or bot.moveSlotItem for exact slots.',
  quit: 'The body\'s connection is managed by the mod.',
  end: 'The body\'s connection is managed by the mod.',
};
// Engines and serializers probe these; they must stay quietly undefined.
const probes = new Set(['then', 'toJSON', 'constructor', 'inspect']);

export function guardBot(bot) {
  return new Proxy(bot, {
    get(target, property, receiver) {
      if (typeof property === 'symbol' || property in target || probes.has(property) || property.startsWith('_'))
        return Reflect.get(target, property, receiver);
      const hint = hints[property] ?? suggestion(property, target);
      throw new TypeError(`bot.${property} is not available in this API subset.${hint ? ' ' + hint : ''}`);
    },
  });
}

// Suggest a close member, or the hint for a close known Mineflayer name.
function suggestion(name, bot) {
  let best, bestDistance = Math.max(2, Math.floor(name.length / 3)) + 1;
  for (const candidate of [...Object.getOwnPropertyNames(bot), ...Object.keys(hints)]) {
    if (candidate.startsWith('_')) continue;
    const distance = editDistance(name.toLowerCase(), candidate.toLowerCase());
    if (distance < bestDistance) { best = candidate; bestDistance = distance; }
  }
  if (!best) return undefined;
  return best in bot ? `Did you mean bot.${best}?` : hints[best];
}

function editDistance(a, b) {
  let previous = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const row = [i];
    for (let j = 1; j <= b.length; j++)
      row[j] = Math.min(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    previous = row;
  }
  return previous[b.length];
}
