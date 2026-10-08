import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../..', import.meta.url));
const reference = createRequire(new URL('./package.json', import.meta.url));
const plugin = createRequire(new URL('../../bb-plugin/package.json', import.meta.url));
assert.equal(reference('prismarine-registry/package.json').version, '1.12.0');
const minecraftData = reference('minecraft-data');
const source = minecraftData('1.21.1');
const sections = ['minecraft:dimension_type', 'minecraft:worldgen/biome', 'minecraft:chat_type'];
const packets = sections.map(id => structuredClone(source.loginPacket.dimensionCodec[id]));
const makeReference = () => reference('prismarine-registry')('1.21.1');
function view(registry) {
  return { dimensions: registry.dimensionsArray, dimensionKeys: Object.keys(registry.dimensionsByName),
    biomes: registry.biomesArray, biomeKeys: Object.keys(registry.biomesByName),
    chat: registry.chatFormattingById, chatNames: registry.chatFormattingByName,
    identities: [registry.dimensionsById[0] === registry.dimensionsArray[0], registry.biomes[0] === registry.biomesArray[0],
      Object.values(registry.chatFormattingById)[0] === Object.values(registry.chatFormattingByName)[0]] };
}
const upstream = makeReference();
for (const packet of packets) assert.equal(upstream.loadDimensionCodec(packet), undefined);
const expected = JSON.parse(JSON.stringify(view(upstream)));
assert(expected.dimensions.length >= 3 && expected.biomes.length > 50 && Object.keys(expected.chat).length >= 7);
const broken = upstream.writeDimensionCodec();
assert.equal(broken['minecraft:dimension_type'].entries[0].value.value.height, undefined);
assert.equal(Array.isArray(broken['minecraft:worldgen/biome'].entries), false);
// A datapack can redefine a vanilla name or add an unknown biome. Compare
// the public loader to upstream, then independently retain the native override.
const customBiome = structuredClone(packets[1]);
customBiome.entries[0].value.value.has_precipitation.value = 1;
customBiome.entries.push({key:'fixture:wetlands',value:structuredClone(customBiome.entries[0].value)});
customBiome.entries.at(-1).value.value.temperature.value = 0.625;
const biomeReference = makeReference();
biomeReference.loadDimensionCodec(customBiome);
assert.equal(biomeReference.biomes[0].has_precipitation, false); // Static source overwrite.
assert.equal(biomeReference.biomes.at(-1).name, 'fixture:wetlands');
const customBiomeExpected = JSON.parse(JSON.stringify(biomeReference.biomesArray));
const format = 'Fixture %s / %s';
const customChat = structuredClone(packets[2]);
customChat.entries[0].value.value.chat.value.translation_key.value = format;
upstream.loadDimensionCodec(customChat);
assert.equal(upstream.chatFormattingById[0].formatString, format);
assert.notEqual(upstream.writeDimensionCodec()['minecraft:chat_type'].entries[0].value.value.chat.value.translation_key.value, format);
console.log(JSON.stringify({ phase: 'reference', dimensions: expected.dimensions.length, biomes: expected.biomes.length, chatTypes: Object.keys(expected.chat).length, writerDefects: ['lost height', 'nonarray biome entries', 'static chat export'] }));
if (process.argv.includes('--reference-only')) process.exit(0);
const { selectRegistryData } = await import('../../bb-plugin/scripting/registry-data.mjs');
const data = selectRegistryData(minecraftData);
const { build } = plugin('esbuild');
const built = await build({ stdin: { contents: `import {createRegistry} from './bb-plugin/scripting/registry.mjs'; const data=${JSON.stringify(data)}; globalThis.make=codecs=>createRegistry(data,codecs); globalThis.packets=${JSON.stringify(packets)}; globalThis.customChat=${JSON.stringify(customChat)}; globalThis.customBiome=${JSON.stringify(customBiome)}; globalThis.view=${view.toString()}; globalThis.registry=make(); for(const packet of packets)registry.loadDimensionCodec(packet);`, resolveDir: root }, bundle: true, write: false, format: 'iife', platform: 'browser', metafile: true });
assert(Object.keys(built.metafile.inputs).every(path => !path.includes('node_modules/minecraft-data') && !path.includes('node_modules/prismarine-nbt')));
assert(Object.values(built.metafile.outputs).every(output => output.imports.length === 0));
const vm = (await plugin('quickjs-emscripten').getQuickJS()).newContext();
vm.runtime.setMemoryLimit(64 * 1024 * 1024); vm.runtime.setMaxStackSize(512 * 1024);
let deadline = Date.now() + 10000; vm.runtime.setInterruptHandler(() => Date.now() > deadline);
function run(code) { deadline = Date.now() + 10000; const out = vm.evalCode(code); if(out.error){const error=vm.dump(out.error);out.error.dispose();throw new Error(JSON.stringify(error));}const value=vm.dump(out.value);out.value.dispose();return value; }
const serializer = reference('minecraft-protocol/src/transforms/serializer').createSerializer({state:'configuration',isServer:true,version:'1.21.1'});
const parser = reference('minecraft-protocol/src/transforms/serializer').createDeserializer({state:'configuration',isServer:false,version:'1.21.1'});
function checkWire(codec) {
  const wire = serializer.createPacketBuffer({name:'registry_data',params:codec});
  const read = parser.parsePacketBuffer(wire).data;
  assert.equal(read.name,'registry_data');
  assert.equal(read.params.id,codec.id);
  assert.equal(read.params.entries.length,codec.entries.length);
  return read.params;
}
try {
  run(built.outputFiles[0].text);
  assert.deepEqual(run('JSON.parse(JSON.stringify(view(registry)))'), expected);
  const exported = run('registry.writeDimensionCodec()');
  for(const packet of packets) assert.deepEqual(checkWire(exported[packet.id]),checkWire(packet));
  run('registry.dimensionsArray[0].minY=-32;registry.dimensionsArray[0].height=256;registry.biomesArray[0].temperature=.375;registry.biomesArray[0].effects.sky_color=123456;registry.loadDimensionCodec(customChat)');
  const changed=run('registry.writeDimensionCodec()');
  const dimension=checkWire(changed[sections[0]]).entries[0].value.value;
  assert.equal(dimension.min_y.value,-32);assert.equal(dimension.height.value,256);
  assert.deepEqual(dimension.effects,packets[0].entries[0].value.value.effects);
  const biome=checkWire(changed[sections[1]]).entries[0].value.value;
  assert.equal(biome.temperature.value,.375);assert.equal(biome.effects.value.sky_color.value,123456);
  assert.deepEqual(biome.effects.value.music,packets[1].entries[0].value.value.effects.value.music);
  assert.equal(checkWire(changed[sections[2]]).entries[0].value.value.chat.value.translation_key.value,format);
  assert.equal(run("(()=>{const copy=registry.writeDimensionCodec();copy['minecraft:chat_type'].entries.length=0;return registry.writeDimensionCodec()['minecraft:chat_type'].entries.length})()"),packets[2].entries.length);
  assert.equal(run("(()=>{const before=JSON.stringify(view(registry));const bad=JSON.parse(JSON.stringify(packets[0]));bad.entries.push({key:'fixture:missing',value:null});try{registry.loadDimensionCodec(bad);return false}catch{return JSON.stringify(view(registry))===before}})()"),true);
  assert.equal(run("(()=>{const before=JSON.stringify(view(registry));registry.loadDimensionCodec({id:'fixture:ignored',entries:[]});return JSON.stringify(view(registry))===before})()"),true);
  assert.equal(run("(()=>{try{registry.loadDimensionCodec({id:'minecraft:dimension_type',entries:Array(4097).fill(packets[0].entries[0])});return false}catch{return true}})()"),true);
  const native=run("(()=>{const r=make(packets);return [r.chatFormattingById[0].id,r.chatFormattingById[0].name,r.biomes[0].name,r.dimensionsById[0].height]})()");
  assert.deepEqual(native,[0,packets[2].entries[0].key,packets[1].entries[0].key.replace('minecraft:',''),expected.dimensions[0].height]);
  assert.deepEqual(run('(()=>{const r=make();r.loadDimensionCodec(customBiome);return JSON.parse(JSON.stringify(r.biomesArray))})()'),customBiomeExpected);
  const nativeCustom = run('(()=>{const r=make([packets[0],customBiome,packets[2]]);return {precipitation:r.biomes[0].has_precipitation,codec:r.writeDimensionCodec()[customBiome.id]}})()');
  assert.equal(nativeCustom.precipitation,1);
  assert.deepEqual(checkWire(nativeCustom.codec),checkWire(customBiome));
  console.log(JSON.stringify({phase:'adapter' ,projectionParity:true,encodedNativeSections:3,mutationsAndPreservedTags:true,atomicBounds:true,nativeIds:true,bundleBytes:built.outputFiles[0].contents.length}));
} finally {vm.dispose();}
