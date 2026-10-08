// Snapshot-driven PC1.21.1 state adapted from Mineflayer4.39.0; see state.LICENSE.
// Version features describe Minecraft, not this adapter's implementation coverage.
export function installState(bot, featureTable) {
  if (!featureTable || typeof featureTable !== 'object' || Array.isArray(featureTable)) {
    throw new TypeError('State requires the pinned PC1.21.1 feature table');
  }
  const features = Object.freeze({ ...featureTable });
  bot.supportFeature = name => Object.hasOwn(features, name) ? features[name] || false : false;
  const time = bot.time = {
    doDaylightCycle: null, bigTime: null, time: null, timeOfDay: null,
    day: null, isDay: null, moonPhase: null, bigAge: null, age: null, clocks: {}
  };
  bot.isRaining = bot.rainState = bot.thunderState = null;
  bot.health = bot.oxygenLevel = bot.isAlive = undefined;
  let previous;

  function long(value, name) {
    if (typeof value !== 'string' || !/^-?\d{1,19}$/.test(value)) throw new TypeError(`Missing native ${name} decimal string`);
    const result = BigInt(value);
    if (BigInt.asIntN(64, result) !== result) throw new RangeError(`Native ${name} exceeds signed long`);
    return result;
  }
  function strength(value, name) {
    if (value === null) return null;
    if (!Number.isFinite(value) || value < 0 || value > 1) throw new RangeError(`Invalid native ${name}`);
    return value;
  }

  // Mutate all public fields first. Root emits these tuples only after the rest
  // of the snapshot is hydrated. First hydration establishes state without events.
  return function updateState(next) {
    const world = next.worldState, body = next.body;
    if (!world || !body) throw new TypeError('State requires worldState and body snapshots');
    const bigTime = long(world.dayTime, 'dayTime'), bigAge = long(world.gameTime, 'gameTime');
    if (typeof world.doDaylightCycle !== 'boolean' || typeof world.isRaining !== 'boolean') {
      throw new TypeError('State requires native daylight-cycle and rain booleans');
    }
    const state = {
      bigTime, bigAge, doDaylightCycle: world.doDaylightCycle,
      isRaining: world.isRaining,
      rainState: strength(world.rainState, 'rainState'),
      thunderState: strength(world.thunderState, 'thunderState'),
      health: Number.isFinite(body.health) ? body.health : undefined,
      airSupply: Number.isInteger(body.airSupply) ? body.airSupply : undefined,
      isAlive: typeof body.alive === 'boolean' ? body.alive : undefined
    };
    time.doDaylightCycle = state.doDaylightCycle;
    // Native dayTime is already the actual clock, not the packet's sign/sentinel
    // encoding for a stopped clock. In particular, frozen zero remains zero.
    time.bigTime = bigTime;
    time.time = Number(bigTime);
    time.timeOfDay = time.time % 24000;
    time.day = Math.floor(time.time / 24000);
    time.isDay = time.timeOfDay >= 0 && time.timeOfDay < 13000;
    time.moonPhase = time.day % 8;
    time.bigAge = bigAge;
    time.age = Number(bigAge);
    bot.isRaining = state.isRaining;
    bot.rainState = state.rainState;
    bot.thunderState = state.thunderState;
    bot.health = state.health;
    bot.isAlive = state.isAlive;
    // Preserve upstream's metadata conversion without a fictitious player cap.
    bot.oxygenLevel = state.airSupply === undefined ? undefined : Math.round(state.airSupply / 15);

    const events = [];
    if (previous) {
      if (previous.bigTime !== bigTime || previous.bigAge !== bigAge || previous.doDaylightCycle !== state.doDaylightCycle) events.push(['time']);
      if (previous.rainState !== state.rainState) events.push(['weatherUpdate']);
      if (previous.isRaining !== state.isRaining) events.push(['rain']);
      if (previous.thunderState !== state.thunderState) events.push(['weatherUpdate']);
      const died = previous.isAlive === true && state.isAlive === false && state.health <= 0;
      if (previous.health !== state.health || died) events.push(['health']);
      if (died) events.push(['death']);
      if (previous.airSupply !== state.airSupply) events.push(['breath']);
    }
    previous = state;
    return events;
  };
}
