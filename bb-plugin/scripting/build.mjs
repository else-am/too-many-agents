import { build } from 'esbuild';
import minecraftData from 'minecraft-data';
import { fileURLToPath } from 'node:url';
import { readFile, writeFile } from 'node:fs/promises';
import { decodeItemTransport } from './item-wire.mjs';

const registry = minecraftData('1.21.1');
const features = JSON.parse(await readFile(new URL('../node_modules/minecraft-data/minecraft-data/data/pc/common/features.json', import.meta.url), 'utf8'));
const featureTable = Object.fromEntries(features.map(({ name }) => [name, registry.supportFeature(name)]));
// Only this game's data belongs in the guest. Do not ship every historical
// Minecraft protocol or give the guest a Node module loader.
const data = JSON.stringify({
  featureTable,
  blocksArray: registry.blocksArray, itemsArray: registry.itemsArray,
  blockCollisionShapes: registry.blockCollisionShapes,
  materials: registry.materials, effectsByName: registry.effectsByName,
  enchantmentsByName: registry.enchantmentsByName, language: registry.language,
  entitiesArray: registry.entitiesArray, recipes: registry.recipes,
});
const licenses = [];
licenses.push(await readFile(new URL('./actions.LICENSE', import.meta.url), 'utf8'));
licenses.push(await readFile(new URL('./state.LICENSE', import.meta.url), 'utf8'));
licenses.push(await readFile(new URL('./blocks.LICENSE', import.meta.url), 'utf8'));
licenses.push(await readFile(new URL('./items.LICENSE', import.meta.url), 'utf8'));
licenses.push(await readFile(new URL('./movements-LICENSE.txt', import.meta.url), 'utf8'));
licenses.push(await readFile(new URL('./planning.LICENSE', import.meta.url), 'utf8'));
licenses.push(await readFile(new URL('./item-wire.LICENSE', import.meta.url), 'utf8'));
licenses.push(await readFile(new URL('./windows.LICENSE', import.meta.url), 'utf8'));
licenses.push(await readFile(new URL('./world-view.LICENSE', import.meta.url), 'utf8'));
licenses.push(await readFile(new URL('./world-queries.LICENSE', import.meta.url), 'utf8'));
licenses.push(await readFile(new URL('./recipes.LICENSE', import.meta.url), 'utf8'));
licenses.push(await readFile(new URL('./chat.LICENSE', import.meta.url), 'utf8'));
licenses.push(await readFile(new URL('./entities.LICENSE', import.meta.url), 'utf8'));
licenses.push(await readFile(new URL('./inventory.LICENSE', import.meta.url), 'utf8'));
licenses.push(await readFile(new URL('./specialized-windows.LICENSE', import.meta.url), 'utf8'));
licenses.push(await readFile(new URL('./books.LICENSE', import.meta.url), 'utf8'));
licenses.push('Mineflayer 4.39.0: adapted waitForTicks implementation\n' + await readFile(new URL('./mineflayer.LICENSE', import.meta.url), 'utf8'));
for (const name of ['mineflayer-pathfinder', 'minecraft-data', 'vec3', 'events']) {
  const root = new URL(`../node_modules/${name}/`, import.meta.url);
  const metadata = JSON.parse(await readFile(new URL('package.json', root), 'utf8'));
  let license;
  try { license = await readFile(new URL('LICENSE', root), 'utf8'); }
  catch (error) {
    if (error.code !== 'ENOENT') throw error;
    // Some upstream npm packages supply only the license declaration. Retain
    // their actual metadata/README rather than inventing a copyright notice.
    license = JSON.stringify(metadata, null, 2) + '\n\n' + await readFile(new URL('README.md', root), 'utf8');
    if (name === 'minecraft-data') license += '\n\n' + await readFile(new URL('minecraft-data/README.md', root), 'utf8');
  }
  licenses.push(`${name} ${metadata.version}\n${metadata.repository?.url ?? ''}\n\n${license}`);
}
await build({
  entryPoints: [fileURLToPath(new URL('./bot.mjs', import.meta.url))],
  outfile: fileURLToPath(new URL('../dist/scripting/bot.js', import.meta.url)),
  bundle: true, platform: 'browser', format: 'iife', globalName: 'MinecraftBot', target: 'es2022',
  legalComments: 'eof', keepNames: true,
  plugins: [{ name: 'minecraft-version-data', setup(build) {
    build.onResolve({ filter: /^minecraft-item-transport$/ }, () => ({ path: 'revive', namespace: 'minecraft-item-transport' }));
    build.onLoad({ filter: /.*/, namespace: 'minecraft-item-transport' }, () => ({ contents: `export const decodeItemTransport = ${decodeItemTransport.toString()};`, loader: 'js' }));
    build.onResolve({ filter: /^(node:)?perf_hooks$/ }, () => ({ path: 'clock', namespace: 'minecraft-clock' }));
    build.onLoad({ filter: /.*/, namespace: 'minecraft-clock' }, () => ({ contents: 'const now = __mcNow; export const performance = { now };', loader: 'js' }));
    build.onResolve({ filter: /^minecraft-version-data$/ }, () => ({ path: '1.21.1', namespace: 'minecraft-version-data' }));
    build.onLoad({ filter: /.*/, namespace: 'minecraft-version-data' }, () => ({ contents: `export default ${data};`, loader: 'js' }));
  } }],
});
await writeFile(new URL('../dist/scripting/licenses.txt', import.meta.url), licenses.join('\n\n-----\n\n'));
