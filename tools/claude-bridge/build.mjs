import { build } from 'esbuild';
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('.', import.meta.url));
const result = await build({ absWorkingDir: root,
  entryPoints: ['bridge.mjs'], outfile: 'dist/bridge.mjs', bundle: true, metafile: true,
  platform: 'node', target: 'node20', format: 'esm', legalComments: 'eof',
  banner: { js: "import { createRequire as __createRequire } from 'node:module'; const require = __createRequire(import.meta.url);" }
});
// The installed native Claude executable is resolved at runtime, never shipped in the mod.
if (Object.keys(result.metafile.inputs).some(path => /(?:^|\/)(?:cli\.js|claude\.exe)$/.test(path)))
  throw new Error('Build unexpectedly included the native Claude CLI.');

// Ship the license of every npm package that ended up in the bundle.
const packages = [...new Set(Object.keys(result.metafile.inputs)
  .map(path => path.match(/node_modules\/((?:@[^/]+\/)?[^/]+)/)?.[1]).filter(Boolean))].sort();
const sections = [];
for (const name of packages) {
  const folder = `${root}node_modules/${name}/`;
  const { version } = JSON.parse(readFileSync(folder + 'package.json', 'utf8'));
  const file = readdirSync(folder).find(f => /^licen[cs]e(\.md|\.txt)?$/i.test(f));
  if (!file) throw new Error(`No license file for bundled package ${name}`);
  sections.push(`${name} ${version}\n\n${readFileSync(folder + file, 'utf8').trim()}`);
}
writeFileSync(root + 'dist/THIRD-PARTY-LICENSES.txt', sections.join('\n\n' + '-'.repeat(72) + '\n\n') + '\n');
