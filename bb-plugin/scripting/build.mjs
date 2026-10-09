import { build } from 'esbuild';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
import minecraftData from 'minecraft-data';
import { fileURLToPath } from 'node:url';
import { readFile, writeFile } from 'node:fs/promises';
import { decodeItemTransport, encodeItemTransport } from './item-wire.mjs';
import { selectRegistryData } from './registry-data.mjs';

// Only this game's data belongs in the guest. Do not ship every historical
// Minecraft protocol or give the guest a Node module loader.
const data = JSON.stringify(selectRegistryData(minecraftData));
for (const name of ['minecraft-data', 'vec3', 'events', 'prismarine-chunk', 'smart-buffer', 'buffer', 'base64-js', 'ieee754']) {
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
  alias: { buffer: require.resolve('buffer/') },
  inject: [fileURLToPath(new URL('./buffer-inject.mjs', import.meta.url))],
  plugins: [{ name: 'minecraft-version-data', setup(build) {
    build.onResolve({ filter: /^minecraft-item-transport$/ }, () => ({ path: 'revive', namespace: 'minecraft-item-transport' }));
    build.onLoad({ filter: /.*/, namespace: 'minecraft-item-transport' }, () => ({ contents: `export const decodeItemTransport = ${decodeItemTransport.toString()}; export const encodeItemTransport = ${encodeItemTransport.toString()};`, loader: 'js' }));
    build.onResolve({ filter: /^(node:)?perf_hooks$/ }, () => ({ path: 'clock', namespace: 'minecraft-clock' }));
    build.onLoad({ filter: /.*/, namespace: 'minecraft-clock' }, () => ({ contents: 'const now = __mcNow; export const performance = { now };', loader: 'js' }));
    build.onResolve({ filter: /^minecraft-version-data$/ }, () => ({ path: '1.21.1', namespace: 'minecraft-version-data' }));
    build.onLoad({ filter: /.*/, namespace: 'minecraft-version-data' }, () => ({ contents: `export default ${data};`, loader: 'js' }));
  } }],
});
