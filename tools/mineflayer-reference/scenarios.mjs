import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createHash, randomUUID } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import mineflayer from 'mineflayer';
import pathfinder from 'mineflayer-pathfinder';
import { Vec3 } from 'vec3';
import { startServer, directory } from './server.mjs';

// ProtoDef logs and drops partial packets without emitting a client error.
// Preserve that diagnostic rather than claiming a clean protocol run.
const craftTrace = process.argv.includes('--craft-trace');
const protocolWarnings = [], originalLog = console.log;
console.log = (...values) => {
  if (typeof values[0] === 'string' && /PartialReadError|^Chunk size is/.test(values[0])
    && protocolWarnings.length < 16) protocolWarnings.push(values[0].slice(0, 4000));
  originalLog(...values);
};
const server = await startServer({ onLine: line => {
  if (/ERROR|WARN|Done \(/.test(line)) console.log(line);
} });
const clientErrors = [];
const bot = mineflayer.createBot({
  host: '127.0.0.1', port: 25575, username: 'Reference', auth: 'offline', version: '1.21.1',
});
bot.loadPlugin(pathfinder.pathfinder);
// Opt-in diagnostics observe packets; they never alter replies or replay clicks.
const transitions = [];
let traceActive = false, traceTruncated = false;
const tracePackets = new Set(['window_click', 'set_slot', 'window_items', 'close_window']);
function trace(direction, name, data) {
  if (!craftTrace || !traceActive || !tracePackets.has(name)) return;
  if (transitions.length >= 512) { traceTruncated = true; return; }
  const encoded = JSON.stringify(data, (_, value) => typeof value === 'bigint' ? value.toString() : value);
  if (encoded.length > 65536) { traceTruncated = true; return; }
  transitions.push({ at: performance.now(), direction, name, data: JSON.parse(encoded) });
}
bot._client.on('packet', (data, meta) => trace('received', meta.name, data));
if (craftTrace) {
  const write = bot._client.write;
  bot._client.write = function (name, data) {
    trace('sent', name, data);
    return write.call(this, name, data);
  };
}
bot.on('error', error => { clientErrors.push(error.message); console.error(error.message); });
const abort = new AbortController();
const timer = setTimeout(() => abort.abort(new Error('Reference scenarios exceeded 120 seconds')), 120_000);

// A chat marker after console commands is a server-thread barrier, not an
// arbitrary delay. World assertions below are evaluated by the server itself.
async function consoleCommands(commands) {
  const marker = randomUUID();
  const messages = [];
  let collect, cancelled;
  const done = new Promise((resolve, reject) => {
    collect = message => { messages.push(message); if (message.includes(marker)) resolve(messages); };
    cancelled = () => reject(abort.signal.reason);
    bot.on('messagestr', collect);
    abort.signal.addEventListener('abort', cancelled, { once: true });
    if (abort.signal.aborted) cancelled();
  });
  try {
    for (const command of commands) server.command(command);
    server.command(`say ${marker}`);
    return await done;
  } finally {
    bot.removeListener('messagestr', collect);
    abort.signal.removeEventListener('abort', cancelled);
  }
}

async function scenario() {
  await once(bot, 'spawn', { signal: abort.signal });
  await bot.waitForChunksToLoad();
  if (process.argv.includes('--world-queries')) return worldQueryScenario();
  if (process.argv.includes('--building') || process.argv.includes('--craft') || craftTrace) return buildingScenarios();
  await consoleCommands([
    'gamerule doDaylightCycle false', 'gamerule doWeatherCycle false',
    'gamerule randomTickSpeed 0', 'time set day', 'weather clear',
    'tp Reference 0.5 -60 0.5',
    'fill -16 -61 -16 16 -61 16 minecraft:stone',
    'fill -16 -60 -16 16 -53 16 minecraft:air',
    'kill @e[type=minecraft:item]',
    'clear Reference', 'give Reference minecraft:diamond_pickaxe',
    'setblock 5 -60 0 minecraft:diamond_ore', 'gamemode survival Reference',
  ]);
  await bot.waitForTicks(5);
  const source = await readFile(new URL('./gather.js', import.meta.url), 'utf8');
  const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
  const started = performance.now();
  const result = await new AsyncFunction('bot', 'goals', source)(bot, pathfinder.goals);
  assert.deepEqual(result, { ore: 'air', diamonds: 1, held: 'diamond_pickaxe' });
  const blockMarker = randomUUID();
  const itemMarker = randomUUID();
  const messages = await consoleCommands([
    `execute if block 5 -60 0 minecraft:air run say ${blockMarker}`,
    `execute if data entity Reference Inventory[{id:"minecraft:diamond",count:1}] run say ${itemMarker}`,
  ]);
  assert(messages.some(message => message.includes(blockMarker)), 'Server must confirm ore removal');
  assert(messages.some(message => message.includes(itemMarker)), 'Server must confirm diamond possession');
  const report = { scenario: 'gather', backend: 'mineflayer', minecraft: '1.21.1',
    mineflayer: '4.39.0', pathfinder: '2.4.5', source,
    sourceSha256: createHash('sha256').update(source).digest('hex'),
    elapsedMs: Math.round(performance.now() - started), result,
    serverConfirmed: true, confirmations: { block: blockMarker, inventory: itemMarker, messages }, clientErrors, protocolWarnings };
  await writeFile(join(directory, 'gather-reference.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
}

async function worldQueryScenario() {
  await consoleCommands([
    'gamerule doDaylightCycle false', 'gamerule doWeatherCycle false',
    'gamerule randomTickSpeed 0', 'time set day', 'weather clear',
    'tp Reference 10.5 -60 3.5',
    'fill 2 -61 -6 20 -61 10 minecraft:stone',
    'fill 2 -60 -6 20 -53 10 minecraft:air',
    'setblock 11 -60 1 minecraft:crafting_table',
    'setblock 11 -60 0 minecraft:stone',
    `setblock 9 -60 2 minecraft:oak_sign{front_text:{messages:['{"text":"Query fixture"}','""','""','""']}}`,
  ]);
  await bot.waitForTicks(5);
  const source = await readFile(new URL('./world-queries-common.js', import.meta.url), 'utf8');
  const report = { scenario:'world-queries-common', backend:'mineflayer', minecraft:'1.21.1',
    mineflayer:'4.39.0', source, sourceSha256:createHash('sha256').update(source).digest('hex'),
    startedAt:new Date().toISOString(), clientErrors, protocolWarnings };
  try {
    const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
    report.result = await new AsyncFunction('bot','Vec3',source)(bot,Vec3);
    const conditions = ['if block 11 -60 1 minecraft:crafting_table',
      'if block 11 -60 0 minecraft:stone', 'if block 9 -60 2 minecraft:oak_sign'];
    const markers = conditions.map(() => randomUUID());
    const messages = await consoleCommands(conditions.map((condition,i) => `execute ${condition} run say ${markers[i]}`));
    assert(markers.every(marker => messages.some(message => message.includes(marker))));
    report.confirmations = { conditions, markers, messages };
    report.serverConfirmed = true;
  } catch (error) { report.error = String(error); throw error; }
  finally {
    report.finishedAt = new Date().toISOString();
    await writeFile(join(directory,'world-queries-reference.json'),JSON.stringify(report,null,2)+'\n');
  }
  console.log(JSON.stringify({ scenario:report.scenario, result:report.result, serverConfirmed:true }));
}

async function buildingScenarios() {
  if (process.argv.includes('--craft')) {
    // Read the previous wall attempt before resetting anything. That script
    // reached its final inventory assertion before the last slot update.
    const conditions = [...[-60, -59].flatMap(y => [32, 33, 34, 35].map(x =>
      `if block ${x} ${y} 8 minecraft:stone`)),
      'unless data entity Reference Inventory[{id:"minecraft:stone"}]'];
    const markers = conditions.map(() => randomUUID());
    const messages = await consoleCommands(conditions.map((condition, i) =>
      `execute ${condition} run say ${markers[i]}`));
    const serverConfirmed = markers.every(marker => messages.some(message => message.includes(marker)));
    await writeFile(join(directory, 'wall-saved-observation.json'), JSON.stringify({
      serverConfirmed, conditions, markers, messages,
      scriptError: 'Wall material consumption differs',
      limitation: 'Independent saved-world outcome; the earlier reference script failed its immediate inventory assertion.'
    }, null, 2) + '\n');
    assert(serverConfirmed, 'Saved reference wall must independently match all eight placements and consumption');
  }
  await consoleCommands([
    'gamerule doDaylightCycle false', 'gamerule doWeatherCycle false',
    'gamerule randomTickSpeed 0', 'time set day', 'weather clear',
    'tp Reference 34.5 -60 5.5',
    'fill 24 -61 0 44 -61 16 minecraft:stone',
    'fill 24 -60 0 44 -53 16 minecraft:air',
    'kill @e[type=minecraft:item]', 'gamemode survival Reference',
  ]);
  const suites = [
    { name: 'wall', file: 'performance-wall.js',
      prepare: ['clear Reference', 'item replace entity Reference hotbar.0 with minecraft:stone 8'],
      expected: { placed: 8, remaining: 0 },
      conditions: [
        ...[-60, -59].flatMap(y => [32, 33, 34, 35].map(x => `if block ${x} ${y} 8 minecraft:stone`)),
        'unless data entity Reference Inventory[{id:"minecraft:stone"}]',
      ] },
    { name: 'gather-craft', file: 'performance-gather-craft.js',
      prepare: ['clear Reference', 'item replace entity Reference hotbar.0 with minecraft:iron_axe',
        'fill 32 -60 8 35 -59 8 minecraft:air',
        'setblock 33 -60 8 minecraft:oak_log', 'setblock 33 -59 8 minecraft:oak_log'],
      expected: { logs: 0, planks: 4, sticks: 8, axeDamage: 2 },
      conditions: ['if block 33 -60 8 minecraft:air', 'if block 33 -59 8 minecraft:air',
        'unless data entity Reference Inventory[{id:"minecraft:oak_log"}]',
        'if data entity Reference Inventory[{id:"minecraft:oak_planks",count:4}]',
        'if data entity Reference Inventory[{id:"minecraft:stick",count:8}]',
        'if data entity Reference Inventory[{id:"minecraft:iron_axe",components:{"minecraft:damage":2}}]'] },
  ];
  for (const suite of suites) {
    if ((process.argv.includes('--craft') || craftTrace) && suite.name !== 'gather-craft') continue;
    await consoleCommands(suite.prepare);
    await bot.waitForTicks(5);
    const source = await readFile(new URL(suite.file, import.meta.url), 'utf8');
    const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
    let result;
    traceActive = craftTrace;
    try {
      result = await new AsyncFunction('bot', 'goals', 'Movements', 'Vec3', source)
        (bot, pathfinder.goals, pathfinder.Movements, Vec3);
    } catch (error) {
      if (craftTrace) {
        // Read the actual server state before disconnect; no recovery mutation.
        const messages = await consoleCommands(['data get entity Reference']);
        traceActive = false;
        const snapshot = () => ({ slots: bot.inventory.slots, cursor: bot.inventory.selectedItem,
          window: bot.currentWindow?.id ?? null });
        const sources = {};
        for (const name of ['craft', 'inventory', 'place_block']) {
          const bytes = await readFile(new URL(`./node_modules/mineflayer/lib/plugins/${name}.js`, import.meta.url));
          sources[name] = createHash('sha256').update(bytes).digest('hex');
        }
        await writeFile(join(directory, `craft-trace-${Date.now()}.json`), JSON.stringify({
          source, sourceSha256: createHash('sha256').update(source).digest('hex'), sources,
          error: String(error), transitions, traceTruncated, observed: snapshot(), serverMessages: messages,
          clientErrors, protocolWarnings,
        }, (_, value) => typeof value === 'bigint' ? value.toString() : value, 2) + '\n');
        throw error;
      }
      await writeFile(join(directory, `${suite.name}-reference.json`), JSON.stringify({
        scenario: suite.name, backend: 'mineflayer', source, error: String(error),
        serverConfirmed: false, clientErrors, protocolWarnings,
      }, null, 2) + '\n');
      throw error;
    }
    traceActive = false;
    if (craftTrace) {
      await writeFile(join(directory, `craft-trace-${Date.now()}.json`), JSON.stringify({
        source, result, transitions, traceTruncated, clientErrors, protocolWarnings,
      }, (_, value) => typeof value === 'bigint' ? value.toString() : value, 2) + '\n');
      return;
    }
    const outcome = Object.fromEntries(Object.keys(suite.expected).map(key => [key, result[key]]));
    assert.deepEqual(outcome, suite.expected);
    const markers = suite.conditions.map(() => randomUUID());
    const messages = await consoleCommands(suite.conditions.map((condition, index) =>
      `execute ${condition} run say ${markers[index]}`));
    assert(markers.every(marker => messages.some(message => message.includes(marker))),
      `Server must independently confirm ${suite.name}`);
    const report = { scenario: suite.name, backend: 'mineflayer', minecraft: '1.21.1',
      mineflayer: '4.39.0', pathfinder: '2.4.5', source,
      sourceSha256: createHash('sha256').update(source).digest('hex'),
      result, outcome, serverConfirmed: true, confirmations: { markers, messages }, clientErrors, protocolWarnings,
      limitations: ['Compare outcomes, not native-player coordinates or timing. No protocol schema patches.'] };
    await writeFile(join(directory, `${suite.name}-reference.json`), JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify({ scenario: suite.name, outcome, serverConfirmed: true }));
  }
}

try {
  await Promise.race([
    scenario(),
    new Promise((_, reject) => abort.signal.addEventListener('abort', () => reject(abort.signal.reason), { once: true })),
  ]);
} finally {
  clearTimeout(timer);
  abort.abort(new Error('Reference scenarios stopped'));
  bot.quit();
  await server.stop();
  console.log = originalLog;
}
