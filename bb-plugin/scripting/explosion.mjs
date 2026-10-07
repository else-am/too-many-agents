// Estimate adapted from Mineflayer 4.39.0 lib/plugins/explosion.js (MIT).
// See mineflayer.LICENSE. Native damage remains authoritative.
import { Vec3 } from 'vec3';
import { createWorldView } from './world-view.mjs';

const unknownCell = Symbol('unknown explosion sample');
const finiteVector = p => p && ['x', 'y', 'z'].every(axis => Number.isFinite(p[axis]));
function attributeValue(attribute) {
  if (!attribute || !Number.isFinite(attribute.value) || !Array.isArray(attribute.modifiers)) return null;
  if (attribute.modifiers.length > 1024) throw new RangeError('Explosion estimate exceeds 1024 attribute modifiers');
  const modifiers = attribute.modifiers;
  if (modifiers.some(m => !Number.isFinite(m.amount) || ![0, 1, 2].includes(m.operation))) return null;
  let base = attribute.value;
  for (const m of modifiers) if (m.operation === 0) base += m.amount;
  let value = base;
  for (const m of modifiers) if (m.operation === 1) value += base * m.amount;
  for (const m of modifiers) if (m.operation === 2) value *= 1 + m.amount;
  return Number.isFinite(value) ? value : null;
}

export function installExplosion(bot) {
  const world = createWorldView(position => {
    const block = bot.blockAt(position);
    if (block === null) throw unknownCell;
    return block;
  });
  bot.getExplosionDamages = (target, source, power, rawDamages = false) => {
    if (!finiteVector(target?.position) || !finiteVector(source) || !Number.isFinite(power) || power < 0)
      throw new RangeError('Explosion estimate requires finite positions and nonnegative power');
    const center = new Vec3(source.x, source.y, source.z);
    const distance = target.position.distanceTo(center), radius = 2 * power;
    if (distance >= radius) return 0;
    if (!Number.isFinite(radius) || radius > 4096) throw new RangeError('Explosion estimate radius exceeds 4096');
    const { width, height } = target;
    if (!Number.isFinite(width) || !Number.isFinite(height)) return null;
    if (width <= 0 || height <= 0) throw new RangeError('Explosion estimate requires positive body dimensions');
    const dx = 1 / (2 * width + 1), dy = 1 / (2 * height + 1);
    const samples = (Math.floor(1 / dx) + 1) ** 2 * (Math.floor(1 / dy) + 1);
    if (!Number.isFinite(samples) || samples > 4096) throw new RangeError('Explosion estimate exceeds 4096 exposure samples');
    const offset = (1 - Math.floor(1 / dx) * dx) / 2;
    let sampled = 0, exposed = 0;
    try {
      for (let y = 0; y <= 1; y += dy) for (let x = 0; x <= 1; x += dx) for (let z = 0; z <= 1; z += dx) {
        if (++sampled > 4096) throw new RangeError('Explosion estimate exceeds 4096 exposure samples');
        const point = target.position.offset(width * (x - .5) + offset, height * y, width * (z - .5) + offset);
        const direction = point.minus(center), range = direction.norm();
        if (world.raycast(center, range === 0 ? new Vec3(0, 0, 0) : direction.scaled(1 / range), range) === null) exposed++;
      }
    } catch (error) { if (error === unknownCell) return null; throw error; }
    const impact = (1 - distance / radius) * exposed / sampled;
    let damage = Math.floor((impact * impact + impact) * 7 * power + 1);
    if (!rawDamages) {
      const attrs = target.attributes ?? {};
      const armor = attributeValue(attrs['generic.armor'] ?? attrs['minecraft:generic.armor']);
      const toughness = attributeValue(attrs['generic.armor_toughness'] ?? attrs['generic.armorToughness'] ?? attrs['minecraft:generic.armor_toughness']);
      if (armor === null || toughness === null) return null;
      const reduction = Math.min(Math.max(armor - damage / (2 + toughness / 4), armor * .2), 20);
      damage *= 1 - reduction / 25;
      // Preserve the pinned estimate's difficulty approximation, not a claim of
      // exact native player damage (which also considers effects/enchantments).
      if (target.type === 'player') {
        const difficulty = { peaceful: 0, easy: 1, normal: 2, hard: 3 }[bot.game?.difficulty];
        if (difficulty === undefined) return null;
        damage *= difficulty * .5;
      }
    }
    return Number.isFinite(damage) ? Math.floor(damage) : null;
  };
}
