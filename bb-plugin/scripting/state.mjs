// Snapshot-driven PC1.21.1 state adapted from Mineflayer4.39.0; see state.LICENSE.
export function installState(bot) {
  const time = bot.time = {
    doDaylightCycle: null, bigTime: null, timeOfDay: null,
    day: null, isDay: null, moonPhase: null, bigAge: null, age: null
  };
  bot.isRaining = bot.rainState = bot.thunderState = null;
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
    // Native dayTime is already the actual clock, not the packet's sign/sentinel
    // encoding for a stopped clock. In particular, frozen zero remains zero.
    const clock = Number(bigTime);
    time.doDaylightCycle = world.doDaylightCycle;
    time.bigTime = bigTime;
    time.timeOfDay = clock % 24000;
    time.day = Math.floor(clock / 24000);
    time.isDay = time.timeOfDay >= 0 && time.timeOfDay < 13000;
    time.moonPhase = time.day % 8;
    time.bigAge = bigAge;
    time.age = Number(bigAge);
    bot.isRaining = world.isRaining;
    bot.rainState = strength(world.rainState, 'rainState');
    bot.thunderState = strength(world.thunderState, 'thunderState');

    const state = {
      health: Number.isFinite(body.health) ? body.health : undefined,
      isAlive: typeof body.alive === 'boolean' ? body.alive : undefined
    };
    const events = [];
    if (previous) {
      const died = previous.isAlive === true && state.isAlive === false && state.health <= 0;
      if (previous.health !== state.health || died) events.push(['health']);
      if (died) events.push(['death']);
    }
    previous = state;
    return events;
  };
}
