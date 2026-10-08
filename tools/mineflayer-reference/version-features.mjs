// Executes the preauthored state-scenarios feature criterion; no world or native actions.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
const reference = createRequire(new URL('./package.json', import.meta.url));
const plugin = createRequire(new URL('../../bb-plugin/package.json', import.meta.url));
const root = fileURLToPath(new URL('../..', import.meta.url));
for (const resolve of [reference, plugin]) assert.equal(resolve('minecraft-data/package.json').version, '3.117.0');
const path = 'minecraft-data/minecraft-data/data/pc/common/features.json';
const names = reference(path).map(feature => feature.name);
assert.equal(new Set(names).size, names.length);
const upstream = reference('minecraft-data')('1.21.1');
const packaged = plugin('minecraft-data')('1.21.1');
const expected = Object.fromEntries(names.map(name => [name, upstream.supportFeature(name)]));
const table = Object.fromEntries(plugin(path).map(({ name }) => [name, packaged.supportFeature(name)]));
assert.deepEqual(table, expected);
// Independent version-contract anchors, including numeric/string-valued queries.
assert.equal(expected.itemsWithComponents, true);
assert.equal(expected.actionIdUsed, false);
assert.equal(expected.metadataIxOfItem, 8);
assert.equal(expected.fishingBiteDelayMaxTicks, 600);
assert.equal(expected.whereDurabilityIsSerialized, 'Damage');
const buildSource = await readFile(new URL('../../bb-plugin/scripting/build.mjs', import.meta.url), 'utf8');
const botSource = await readFile(new URL('../../bb-plugin/scripting/bot.mjs', import.meta.url), 'utf8');
assert(buildSource.includes('Object.fromEntries(features.map(({ name }) => [name, registry.supportFeature(name)]))'));
assert.match(botSource, /installState\(bot, data\.featureTable\)/);
assert.match(botSource, /registry\.supportFeature = bot\.supportFeature/);
const bundle = await plugin('esbuild').build({stdin:{resolveDir:root,
  contents:`import {installState} from './bb-plugin/scripting/state.mjs';
    const table=${JSON.stringify(table)};const bot={};installState(bot,table);
    globalThis.result=Object.fromEntries(${JSON.stringify(names)}.map(name=>[name,bot.supportFeature(name)]));
    globalThis.unknown=['missing-feature','constructor','toString','__proto__'].map(name=>bot.supportFeature(name));
    table.itemsWithComponents=false;table.metadataIxOfItem=-1;
    globalThis.retained=[bot.supportFeature('itemsWithComponents'),bot.supportFeature('metadataIxOfItem')];`},
  bundle:true,write:false,platform:'browser',format:'iife',metafile:true});
assert(Object.values(bundle.metafile.outputs).every(output => output.imports.length === 0));
const vm = (await plugin('quickjs-emscripten').getQuickJS()).newContext();
vm.runtime.setMemoryLimit(64*1024*1024);vm.runtime.setMaxStackSize(512*1024);
const deadline=Date.now()+10000;vm.runtime.setInterruptHandler(()=>Date.now()>deadline);
function evaluate(code) {const result=vm.evalCode(code),handle=result.error??result.value;
  const value=vm.dump(handle);handle.dispose();if(result.error)throw new Error(JSON.stringify(value));return value;}
let actual,unknown,retained;
try {evaluate(bundle.outputFiles[0].text);actual=evaluate('result');unknown=evaluate('unknown');retained=evaluate('retained');
  assert.deepEqual(actual,expected);assert.deepEqual(unknown,[false,false,false,false]);assert.deepEqual(retained,[true,8]);}
finally {vm.dispose();}
const sha=data=>createHash('sha256').update(data).digest('hex');
const report={result:'passed',minecraft:'1.21.1',dataVersion:'3.117.0',featureCount:names.length,values:actual,
  unknown,retained,referenceUnknownTypes:['missing-feature','constructor','toString','__proto__'].map(name=>typeof upstream.supportFeature(name)),
  sourceSha256:sha(await readFile(new URL(import.meta.url))),
  stateSha256:sha(await readFile(new URL('../../bb-plugin/scripting/state.mjs',import.meta.url))),
  featureDataSha256:sha(await readFile(reference.resolve(path))),buildSourceSha256:sha(buildSource),botSourceSha256:sha(botSource),
  bundleSha256:sha(bundle.outputFiles[0].contents),memoryBytes:64*1024*1024,stackBytes:512*1024,
  limitation:'Version queries only; no native feature implementation, state hydration, world, or packaged runtime initialization claim.'};
await writeFile(new URL('../../run/mineflayer-reference/version-features.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({result:report.result,featureCount:names.length,unknown,retained,limitation:report.limitation}));
