import assert from 'node:assert/strict';
import { once } from 'node:events';
import { randomUUID } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import mineflayer from 'mineflayer';
import pathfinder from 'mineflayer-pathfinder';
import { startServer, directory } from './server.mjs';

const server = await startServer({ onLine: line => {
  if (/ERROR|WARN|Done \(/.test(line)) console.log(line);
} });
const bot = mineflayer.createBot({
  host: '127.0.0.1', port: 25575, username: 'Reference', auth: 'offline', version: '1.21.1',
});
bot.loadPlugin(pathfinder.pathfinder);
bot.on('error', error => console.error(error.message));
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
    `execute if items entity Reference inventory.* minecraft:diamond run say ${itemMarker}`,
  ]);
  assert(messages.some(message => message.includes(blockMarker)), 'Server must confirm ore removal');
  assert(messages.some(message => message.includes(itemMarker)), 'Server must confirm diamond possession');
  const report = { scenario: 'gather', backend: 'mineflayer', elapsedMs: Math.round(performance.now() - started), result, serverConfirmed: true };
  await writeFile(join(directory, 'gather-reference.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
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
}
