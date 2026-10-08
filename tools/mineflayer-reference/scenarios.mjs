import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createHash, randomUUID } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import mineflayer from 'mineflayer';
import { Vec3 } from 'vec3';
import { startServer, directory } from './server.mjs';

// ProtoDef logs and drops partial packets without emitting a client error.
// Preserve that diagnostic rather than claiming a clean protocol run.
const craftTrace = process.argv.includes('--craft-trace');
if (process.argv.includes('--placement-refusal')) {
  const properties = await readFile(join(directory, 'server.properties'), 'utf8');
  assert(/^spawn-animals=true\s*$/m.test(properties),
    'Placement refusal requires spawn-animals=true in the isolated reference server; false discards even summoned cows');
}
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
  if (process.argv.includes('--placement-refusal')) return placementRefusalScenario();
  if (process.argv.includes('--craft-missing')) return missingMaterialsScenario();
  if (process.argv.includes('--dig-unsuitable-tool') || process.argv.includes('--dig-cancel') || process.argv.includes('--dig-disappearing')) return miningScenario();
  if (process.argv.includes('--equipment') || process.argv.includes('--equipment-common')) return equipmentScenario();
  if (process.argv.includes('--world-queries')) return worldQueryScenario();
  if (process.argv.includes('--container')) return containerScenario();
  if (process.argv.includes('--wall-convergence')) return wallConvergenceScenario();
  if (process.argv.includes('--building')) return buildingScenarios();
  return missingMaterialsScenario();
}

async function missingMaterialsScenario() {
  const source = await readFile(new URL('./craft-missing-common.js', import.meta.url), 'utf8');
  const report = { scenario:'craft-missing',backend:'mineflayer',minecraft:'1.21.1',mineflayer:'4.39.0',
    source,sourceSha256:createHash('sha256').update(source).digest('hex'),startedAt:new Date().toISOString(),
    clientErrors,protocolWarnings,serverConfirmed:false };
  try {
    await consoleCommands(['gamemode survival Reference','clear Reference']);
    await bot.waitForTicks(5);
    report.invokedAt = new Date().toISOString();
    report.result = await new (Object.getPrototypeOf(async function(){}).constructor)('bot','Vec3',source)(bot,Vec3);
    const marker = randomUUID();
    const messages = await consoleCommands([`execute unless data entity Reference Inventory[] run say ${marker}`]);
    report.confirmations = {marker,messages};
    assert(messages.some(message => message.includes(marker)), 'Server inventory must remain empty');
    report.serverConfirmed = true;
  } catch (error) { report.error = String(error); throw error; }
  finally {
    report.finishedAt = new Date().toISOString();
    await writeFile(join(directory,'craft-missing-reference.json'),JSON.stringify(report,null,2)+'\n');
  }
  console.log(JSON.stringify({scenario:report.scenario,result:report.result,serverConfirmed:report.serverConfirmed}));
}

async function miningScenario() {
  const cancelling = process.argv.includes('--dig-cancel');
  const disappearing = process.argv.includes('--dig-disappearing');
  const scenario = disappearing ? 'dig-disappearing' : cancelling ? 'dig-cancel' : 'dig-unsuitable-tool';
  const source = await readFile(new URL(disappearing ? './dig-disappearing-common.js' : cancelling ? './dig-cancel-common.js' : './dig-unsuitable-tool.js', import.meta.url), 'utf8');
  const report = { scenario,backend:'mineflayer',minecraft:'1.21.1',mineflayer:'4.39.0',
    source,sourceSha256:createHash('sha256').update(source).digest('hex'),startedAt:new Date().toISOString(),
    clientErrors,protocolWarnings,serverConfirmed:false };
  try {
    await consoleCommands([
      'fill 58 -61 -2 64 -61 6 minecraft:stone','fill 58 -60 -2 64 -54 6 minecraft:air',
      'kill @e[type=minecraft:item,x=58,y=-60,z=-2,dx=6,dy=6,dz=8]',
      'tp Reference 60.5 -60.0 0.5','gamemode survival Reference','clear Reference',
      'effect clear Reference','setblock 60 -60 3 minecraft:stone',
    ]);
    await bot.waitForTicks(5);
    report.invokedAt = new Date().toISOString();
    report.result = await new (Object.getPrototypeOf(async function(){}).constructor)('bot','Vec3','removeFixtureTarget',source)(bot,Vec3,async () => {
      report.fixtureRemovalRequestedAt = new Date().toISOString();
      report.fixtureRemovalMessages = await consoleCommands(['setblock 60 -60 3 minecraft:air']);
    });
    const conditions = [`if block 60 -60 3 minecraft:${cancelling ? 'stone' : 'air'}`,'unless data entity Reference Inventory[]',
      'unless entity @e[type=minecraft:item,x=58,y=-60,z=-2,dx=6,dy=6,dz=8]'];
    const markers = conditions.map(() => randomUUID());
    const messages = await consoleCommands(conditions.map((condition,index) => `execute ${condition} run say ${markers[index]}`));
    report.confirmations = {conditions,markers,messages};
    assert(markers.every(marker => messages.some(message => message.includes(marker))), 'Server no-harvest outcome must match');
    report.serverConfirmed = true;
  } catch (error) { report.error = String(error); throw error; }
  finally {
    report.finishedAt = new Date().toISOString();
    await writeFile(join(directory,`${scenario}-reference.json`),JSON.stringify(report,null,2)+'\n');
  }
  console.log(JSON.stringify({scenario:report.scenario,result:report.result,serverConfirmed:report.serverConfirmed}));
}

async function equipmentScenario() {
  const ordinary = process.argv.includes('--equipment-common');
  const source = await readFile(new URL(ordinary ? './equipment-common.js' : './inventory-equipment.js', import.meta.url), 'utf8');
  const scenario = ordinary ? 'equipment-common' : 'equipment';
  const report = { scenario,backend:'mineflayer',minecraft:'1.21.1',mineflayer:'4.39.0',
    source,sourceSha256:createHash('sha256').update(source).digest('hex'),startedAt:new Date().toISOString(),
    clientErrors,protocolWarnings,serverConfirmed:false };
  try {
    await consoleCommands([
      'fill 10 -61 -1 15 -61 4 minecraft:stone','fill 10 -60 -1 15 -55 4 minecraft:air',
      'tp Reference 12.5 -60.0 0.5','gamemode survival Reference','clear Reference',
      'setblock 12 -60 2 minecraft:chest',
      ordinary ? 'item replace entity Reference inventory.1 with minecraft:iron_helmet 1' : 'item replace block 12 -60 2 container.2 with minecraft:iron_helmet 1',
      ordinary ? 'item replace entity Reference inventory.2 with minecraft:shield 1' : 'item replace block 12 -60 2 container.3 with minecraft:shield 1',
      'item replace entity Reference inventory.0 with minecraft:stone 17',
      'item replace entity Reference hotbar.6 with minecraft:diamond_pickaxe 1',
      `item replace entity Reference hotbar.0 with minecraft:diamond_sword[minecraft:damage=7,minecraft:custom_name='"Equipment sword"'] 1`,
    ]);
    bot.setQuickBarSlot(6);
    await bot.waitForTicks(5);
    report.invokedAt = new Date().toISOString();
    report.result = await new (Object.getPrototypeOf(async function(){}).constructor)('bot','Vec3',source)(bot,Vec3);
    const conditions = ['if data entity Reference Inventory[{id:"minecraft:stone",count:17}]',
      'if data entity Reference Inventory[{id:"minecraft:iron_helmet",count:1}]',
      'if data entity Reference Inventory[{id:"minecraft:shield",count:1}]',
      'if data entity Reference Inventory[{id:"minecraft:diamond_sword",components:{"minecraft:damage":7}}]',
      'if data entity Reference {SelectedItemSlot:6}'];
    if (ordinary) conditions.push('unless data entity Reference Inventory[{Slot:103b}]', 'unless data entity Reference Inventory[{Slot:-106b}]');
    const markers = conditions.map(() => randomUUID());
    const messages = await consoleCommands(conditions.map((condition,index) => `execute ${condition} run say ${markers[index]}`));
    report.confirmations = {conditions,markers,messages};
    assert(markers.every(marker => messages.some(message => message.includes(marker))), 'Server equipment outcome must match');
    report.serverConfirmed = true;
  } catch (error) { report.error = String(error); throw error; }
  finally {
    report.finishedAt = new Date().toISOString();
    await writeFile(join(directory,`${scenario}-reference.json`),JSON.stringify(report,null,2)+'\n');
  }
  console.log(JSON.stringify({scenario:report.scenario,result:report.result,serverConfirmed:report.serverConfirmed}));
}

async function placementRefusalScenario() {
  const source = await readFile(new URL('./placement-refusal.js', import.meta.url), 'utf8');
  const report = { scenario:'placement-refusal',backend:'mineflayer',minecraft:'1.21.1',mineflayer:'4.39.0',
    source,sourceSha256:createHash('sha256').update(source).digest('hex'),startedAt:new Date().toISOString(),
    clientErrors,protocolWarnings,serverConfirmed:false };
  try {
    await consoleCommands([
      'gamerule doDaylightCycle false','gamerule doWeatherCycle false','time set day','weather clear',
      'fill 44 -61 3 53 -61 12 minecraft:stone','fill 44 -60 3 53 -54 12 minecraft:air',
      'kill @e[tag=tma_placement_refusal]','tp Reference 48.5 -60.0 5.5',
      'gamemode survival Reference','clear Reference',
      'item replace entity Reference hotbar.0 with minecraft:stone 1',
      'summon minecraft:cow 48.5 -60.0 8.5 {Tags:["tma_placement_refusal"],NoAI:1b,PersistenceRequired:1b}',
    ]);
    bot.setQuickBarSlot(0);
    await bot.waitForTicks(5);
    const cow = Object.values(bot.entities).find(entity => entity.name === 'cow' && entity.position.distanceTo(new Vec3(48.5,-60,8.5)) < .1);
    assert(cow && cow.width > 0 && cow.height > 0, 'Stationary cow must occupy the intended destination');
    report.obstruction = {id:cow.id,position:cow.position,width:cow.width,height:cow.height};
    report.invokedAt = new Date().toISOString();
    report.result = await new (Object.getPrototypeOf(async function(){}).constructor)('bot','Vec3',source)(bot,Vec3);
    const conditions = ['if block 48 -60 8 minecraft:air','if block 48 -61 8 minecraft:stone',
      'if data entity Reference Inventory[{id:"minecraft:stone",count:1}]',
      'if entity @e[type=minecraft:cow,tag=tma_placement_refusal,x=48,y=-60,z=8,dx=1,dy=1,dz=1,limit=1]'];
    const markers = conditions.map(() => randomUUID());
    const messages = await consoleCommands(conditions.map((condition,index) => `execute ${condition} run say ${markers[index]}`));
    report.confirmations = {conditions,markers,messages};
    assert(markers.every(marker => messages.some(message => message.includes(marker))), 'Server refusal outcome must match');
    report.serverConfirmed = true;
  } catch (error) { report.error = String(error); throw error; }
  finally {
    report.finishedAt = new Date().toISOString();
    await writeFile(join(directory,'placement-refusal-reference.json'),JSON.stringify(report,null,2)+'\n');
    if (!abort.signal.aborted) await consoleCommands(['kill @e[tag=tma_placement_refusal]']);
  }
  console.log(JSON.stringify({scenario:report.scenario,result:report.result,serverConfirmed:report.serverConfirmed}));
}

async function wallConvergenceScenario() {
  await consoleCommands([
    'gamerule doDaylightCycle false', 'gamerule doWeatherCycle false',
    'gamerule randomTickSpeed 0', 'time set day', 'weather clear',
    'fill 24 -61 0 44 -61 16 minecraft:stone',
    'fill 24 -60 0 44 -53 16 minecraft:air',
    'tp Reference 34.5 -60.0 5.5', 'gamemode survival Reference', 'clear Reference',
    'item replace entity Reference hotbar.0 with minecraft:stone 8',
  ]);
  await bot.waitForTicks(5);
  bot.setQuickBarSlot(0);
  const source = await readFile(new URL('./wall-convergence.js', import.meta.url), 'utf8');
  const report = { scenario:'wall-convergence', backend:'mineflayer', minecraft:'1.21.1',
    mineflayer:'4.39.0', source, sourceSha256:createHash('sha256').update(source).digest('hex'),
    startedAt:new Date().toISOString(), clientErrors, protocolWarnings };
  try {
    const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
    report.result = await new AsyncFunction('bot','Vec3',source)(bot,Vec3);
    const conditions = [
      ...[-60,-59].flatMap(y => [32,33,34,35].map(x => `if block ${x} ${y} 8 minecraft:stone`)),
      'unless data entity Reference Inventory[{id:"minecraft:stone"}]',
    ];
    const markers = conditions.map(() => randomUUID());
    const messages = await consoleCommands(conditions.map((condition,i) => `execute ${condition} run say ${markers[i]}`));
    report.confirmations = { conditions, markers, messages };
    report.serverConfirmed = markers.every(marker => messages.some(message => message.includes(marker)));
    assert(report.serverConfirmed, 'Server must confirm all eight wall blocks and material consumption');
  } catch (error) { report.error = String(error); throw error; }
  finally {
    report.finishedAt = new Date().toISOString();
    await writeFile(join(directory,'wall-convergence-reference.json'),JSON.stringify(report,null,2)+'\n');
  }
  console.log(JSON.stringify({scenario:report.scenario,result:report.result,serverConfirmed:report.serverConfirmed}));
}

async function containerScenario() {
  await consoleCommands([
    'gamerule doDaylightCycle false', 'gamerule doWeatherCycle false',
    'gamerule randomTickSpeed 0', 'time set day', 'weather clear',
    'fill 2 -61 -6 20 -61 10 minecraft:stone',
    'fill 2 -60 -6 20 -53 10 minecraft:air',
    'tp Reference 10.5 -60.0 2.5', 'gamemode survival Reference', 'clear Reference',
    'item replace entity Reference hotbar.6 with minecraft:diamond_pickaxe',
    'setblock 12 -60 2 minecraft:chest{Items:[{Slot:0b,id:"minecraft:stone",count:60},{Slot:1b,id:"minecraft:stone",count:20}]}',
  ]);
  await bot.waitForTicks(5);
  const source = await readFile(new URL('./inventory-chest-common.js', import.meta.url), 'utf8');
  const report = { scenario:'inventory-chest-common', backend:'mineflayer', minecraft:'1.21.1',
    mineflayer:'4.39.0', source, sourceSha256:createHash('sha256').update(source).digest('hex'),
    startedAt:new Date().toISOString(), clientErrors, protocolWarnings };
  try {
    const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
    report.result = await new AsyncFunction('bot','Vec3',source)(bot,Vec3);
    const conditions = [
      'if data block 12 -60 2 Items[{id:"minecraft:stone",count:63}]',
      'unless data block 12 -60 2 Items[1]',
      'if data entity Reference Inventory[{id:"minecraft:stone",count:17}]',
      'if data entity Reference {SelectedItemSlot:6,SelectedItem:{id:"minecraft:diamond_pickaxe"}}',
    ];
    const markers = conditions.map(() => randomUUID());
    const messages = await consoleCommands(conditions.map((condition,i) => `execute ${condition} run say ${markers[i]}`));
    report.confirmations = { conditions, markers, messages };
    report.serverConfirmed = markers.every(marker => messages.some(message => message.includes(marker)));
    assert(report.serverConfirmed, 'Server must confirm container transfer and selection');
  } catch (error) { report.error = String(error); throw error; }
  finally {
    report.finishedAt = new Date().toISOString();
    await writeFile(join(directory,'container-reference.json'),JSON.stringify(report,null,2)+'\n');
  }
  console.log(JSON.stringify({scenario:report.scenario,result:report.result,serverConfirmed:report.serverConfirmed}));
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

  ];
  for (const suite of suites) {
    await consoleCommands(suite.prepare);
    await bot.waitForTicks(5);
    const source = await readFile(new URL(suite.file, import.meta.url), 'utf8');
    const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
    let result;
    traceActive = craftTrace;
    try {
      result = await new AsyncFunction('bot', 'Vec3', source)(bot, Vec3);
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
      mineflayer: '4.39.0', source,
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
