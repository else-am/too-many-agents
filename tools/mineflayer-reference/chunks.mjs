// Source scenarios authored before the adapter. No game, client, or native fixture.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
const root = fileURLToPath(new URL('../..', import.meta.url));
const referenceRoot = resolve(process.env.MINEFLAYER_REFERENCE_ROOT ?? resolve(root, 'tools/mineflayer-reference'));
const pluginRoot = resolve(process.env.MINEFLAYER_PLUGIN_ROOT ?? resolve(root, 'bb-plugin'));
const reference = createRequire(resolve(referenceRoot, 'package.json'));
const plugin = createRequire(resolve(pluginRoot, 'package.json'));
for (const [name, version] of Object.entries({ 'prismarine-chunk': '1.41.0', 'smart-buffer': '4.2.0', buffer: '6.0.3', 'minecraft-data': '3.117.0' })) assert.equal(reference(`${name}/package.json`).version, version);
const registry = reference('minecraft-data')('1.21.1');
const Block = reference('prismarine-block')(registry);
const Chunk = reference('prismarine-chunk/src/pc/1.18/ChunkColumn')(Block, registry);
const fields = ['blocksArray', 'biomesArray', 'blockCollisionShapes', 'materials', 'effectsByName', 'enchantmentsByName', 'language'];
const data = Object.fromEntries(fields.map(k => [k, registry[k]]));
const ids = Object.fromEntries(['air', 'stone', 'water', 'oak_slab', 'chest', 'cave_air'].map(n => [n, registry.blocksByName[n].defaultState]));
function exercise(Chunk, Block, ids) {
  const c = new Chunk({ minY: -64, worldHeight: 32 });
  const points = [{ x: 0, y: -64, z: 0 }, { x: 15, y: -49, z: 15 }, { x: 0, y: -48, z: 15 }, { x: 15, y: -33, z: 0 }];
  const states = [ids.stone, ids.water, ids.oak_slab, ids.chest];
  for (let i = 0; i < points.length; i++) {
    c.setBlockStateId(points[i], states[i]); c.setBiomeId(points[i], i + 1);
    c.setBlockLight(points[i], i + 2); c.setSkyLight(points[i], 15 - i);
  }
  const tag = { type: 'compound', value: { x: { type: 'int', value: 32 }, y: { type: 'int', value: -64 }, z: { type: 'int', value: -16 }, marker: { type: 'string', value: 'kept' } } };
  c.setBlockEntity(points[0], tag);
  const describe = p => { const b = c.getBlock(p); return { state: b.stateId, type: c.getBlockType(p), data: c.getBlockData(p), biome: c.getBiome(p), light: b.light, sky: b.skyLight, typed: b instanceof Block, tag: b.entity }; };
  const result = { points: points.map(describe), section: [c.getSection(points[0]) === c.sections[0], c.getSectionAtIndex(-3) === c.sections[1]], outside: c.getBlockStateId({ x: 0, y: 32, z: 0 }), mask: c.getMask(), dumpBiomes: c.dumpBiomes(), empty: c.sections.map(s => s.isEmpty()) };
  const binary = c.dump(); const copy = new Chunk({ minY: -64, worldHeight: 32 }); copy.load(binary);
  const light = c.dumpLight(); copy.loadParsedLight(light.skyLight, light.blockLight, light.skyLightMask, light.blockLightMask, light.emptySkyLightMask, light.emptyBlockLightMask);
  result.roundtrip = points.map(p => [copy.getBlockStateId(p), copy.getBiome(p), copy.getBlockLight(p), copy.getSkyLight(p)]);
  result.bytes = Array.from(binary); result.light = JSON.parse(JSON.stringify(light));
  c.removeBlockEntity(points[0]); result.removed = c.getBlockEntity(points[0]) === undefined;
  const b = Block.fromStateId(ids.stone, 3); b.light = 9; b.skyLight = 7; b.entity = tag;
  c.setBlock(points[0], b); result.set = describe(points[0]); c.setBlockType(points[1], b.type); c.setBlockData(points[1], 0); result.type = c.getBlockStateId(points[1]);
  const small = new Chunk({ minY: 0, worldHeight: 16 }); let calls = 0;
  small.initialize((x, y, z) => { calls++; return x === 1 && y === 2 && z === 3 ? b : null; });
  result.initialize = [calls, small.getBlockStateId({ x: 1, y: 2, z: 3 }), small.getBlockStateId({ x: 0, y: 0, z: 0 })];
  const direct = new Chunk({ minY: 0, worldHeight: 16 });
  for (let i = 0; i < 300; i++) direct.setBlockStateId({ x: i & 15, y: i >> 8, z: (i >> 4) & 15 }, i);
  const loaded = new Chunk({ minY: 0, worldHeight: 16 }); loaded.load(direct.dump());
  result.direct = [direct.sections[0].data.data.bitsPerValue, Array.from({ length: 300 }, (_, i) => loaded.getBlockStateId({ x: i & 15, y: i >> 8, z: (i >> 4) & 15 })), Array.from(direct.dump())];
  const json = Chunk.fromJson(c.toJson()); result.json = points.map(p => [json.getBlockStateId(p), json.getBiome(p), json.getBlockLight(p), json.getSkyLight(p)]);
  const disk = new Chunk({ minY: -64, worldHeight: 32 });
  disk.loadSection(-4, { palette: [{ Name: 'minecraft:stone' }], bitsPerBlock: 4 }, { palette: ['minecraft:plains'], bitsPerBiome: 1 });
  result.disk = [disk.getBlockStateId(points[0]), disk.getBiome(points[0])];
  result.methods = [...new Set((function* () { for (let p = c; p; p = Object.getPrototypeOf(p)) for (const k of Object.getOwnPropertyNames(p)) if (k !== 'constructor' && !k.startsWith('_') && typeof c[k] === 'function') yield k; })())].sort();
  return JSON.parse(JSON.stringify(result));
}
const expected = exercise(Chunk, Block, ids);
assert.deepEqual(expected.roundtrip.map(v => v[0]), [ids.stone, ids.water, ids.oak_slab, ids.chest]);
assert.deepEqual(expected.direct[1], Array.from({ length: 300 }, (_, i) => i));
// Native-format source fixtures: 24 singleton sections and little-endian nibbles.
// LevelChunkSection.write: short count; block palette; biome palette.
const one = Buffer.from([0x10, 0, 0, ids.stone, 0, 0, registry.biomesByName.plains.id, 0]);
const nibble = Buffer.alloc(2048); nibble[0] = 0x21; nibble[1] = 0x43; nibble[128] = 0xba;
const row = { x: 2, z: -1, minY: -64, worldHeight: 384, data: Buffer.concat(Array(24).fill(one)).toString('base64'), skyLight: Array(26).fill(15), blockLight: Array.from({length:26}, (_,i) => i === 1 ? nibble.toString('base64') : 0), blockEntities: [{ x: 0, y: -64, z: 0, nbt: { type: 'compound', value: { marker: { type: 'string', value: 'native-format' } } } }] };
const tail = Buffer.concat(Array(23).fill(one));
const badIndex = Buffer.alloc(2048); badIndex[7] = 1;
const badRows = [
  Buffer.from([16, 0, 0, 255, 255, 127, 0, 0, 1, 0]), // Unknown state.
  Buffer.from([16, 0, 0, ids.stone, 0, 0, 127, 0]), // Unknown biome.
  Buffer.from([0, 1, 4, 1, ids.stone, 0]), // Invalid packed length.
  Buffer.concat([Buffer.from([0, 1, 4, 1, ids.stone, 128, 2]), badIndex, Buffer.from([0, 1, 0])]), // Palette index1, length1.
  Buffer.from([0, 0, 0, 0, 128, 0, 0, 1, 0]), // Noncanonical zero.
].map(first => ({ ...row, data: Buffer.concat([first, tail]).toString('base64') }));
console.log('Reference scenarios passed: public column operations, palette promotion, wire/light/JSON/disk round trips.');
if (process.argv.includes('--reference-only')) process.exit(0);
const { build } = plugin('esbuild');
const bufferPath = reference.resolve('buffer/');
const entry = `import {createChunkClass} from ${JSON.stringify(resolve(root, 'bb-plugin/scripting/chunks.mjs'))}; import {createBlockClass} from ${JSON.stringify(resolve(root, 'bb-plugin/scripting/blocks.mjs'))}; const registry=${JSON.stringify(data)}; const Block=createBlockClass(registry); const Chunk=createChunkClass(registry,Block); globalThis.fixture={Chunk,Block,ids:${JSON.stringify(ids)},row:${JSON.stringify(row)},badRows:${JSON.stringify(badRows)}}; globalThis.result=(${exercise.toString()})(Chunk,Block,fixture.ids);`;
const built = await build({ stdin: { contents: entry, resolveDir: root }, bundle: true, write: false, format: 'iife', platform: 'browser', metafile: true, nodePaths: [resolve(referenceRoot, 'node_modules'), resolve(pluginRoot, 'node_modules')], alias: { buffer: bufferPath }, inject: ['chunk-buffer'], plugins: [{ name: 'chunk-buffer', setup(build) { build.onResolve({ filter: /^chunk-buffer$/ }, () => ({ path: 'chunk-buffer', namespace: 'shim' })); build.onLoad({ filter: /.*/, namespace: 'shim' }, () => ({ resolveDir: root, contents: `export {Buffer} from ${JSON.stringify(bufferPath)}` })); } }] });
for (const output of Object.values(built.metafile.outputs)) assert.equal(output.imports.length, 0);
for (const input of Object.keys(built.metafile.inputs)) assert(!/prismarine-chunk\/(index|src\/index|src\/bedrock)|minecraft-data\//.test(input), input);
const vm = (await plugin('quickjs-emscripten').getQuickJS()).newContext();
vm.runtime.setMemoryLimit(64 * 1024 * 1024); vm.runtime.setMaxStackSize(512 * 1024);
let deadline; vm.runtime.setInterruptHandler(() => Date.now() > deadline);
function evaluate(code) { deadline = Date.now() + 30000; const r = vm.evalCode(code); const h = r.error ?? r.value; const value = vm.dump(h); h.dispose(); if (r.error) throw new Error(JSON.stringify(value)); return value; }
try {
  evaluate(built.outputFiles[0].text); assert.deepEqual(JSON.parse(evaluate('JSON.stringify(result)')), expected);
  const corrections = evaluate(`JSON.stringify((() => {
    const {Chunk,ids}=fixture, p={x:0,y:0,z:0}; const c=new Chunk({minY:0,worldHeight:16});
    for(let i=0;i<300;i++)c.setBlockStateId({x:i&15,y:i>>8,z:(i>>4)&15},i);
    c.emptyBlockLightMask.set(1,1); const copy=Chunk.fromJson(c.toJson());
    const tag={x:{type:'int',value:33},y:{type:'int',value:0},z:{type:'int',value:-17}};
    c.loadBlockEntities([tag]); const high=new Chunk({minY:32,worldHeight:16});
    high.loadSection(2,{palette:[{Name:'minecraft:stone'}],bitsPerBlock:4},{palette:['minecraft:plains'],bitsPerBiome:1});
    const b=new Chunk({minY:0,worldHeight:16});for(let i=0;i<12;i++)b.setBiome({x:(i&3)*4,y:(i>>4)*4,z:((i>>2)&3)*4},i);
    const b2=new Chunk({minY:0,worldHeight:16});b2.load(b.dump());
    return [copy.getBlockStateId({x:11,y:1,z:2}),copy.emptyBlockLightMask.get(1),c.getBiomeData(p).id,c.getBlockEntity({x:1,y:0,z:15})===tag,high.getBlockStateId({x:0,y:32,z:0}),b2.getBiome({x:12,y:0,z:8})];
  })())`);
  assert.deepEqual(JSON.parse(corrections), [299, 1, 0, true, ids.stone, 11]);
  const start = Date.now();
  const hydrated = evaluate(`JSON.stringify((()=>{const {Chunk,row,Block}=fixture;globalThis.columns=Array.from({length:25},()=>Chunk.fromSnapshot(row));const c=columns[24],p={x:0,y:-64,z:0};return [columns.length,c.getBlock(p) instanceof Block,c.getBlockStateId(p),[0,1,2,3].map(x=>c.getBlockLight({x,y:-64,z:0})),c.getBlockLight({x:0,y:-63,z:0}),c.getBlockEntity(p).value.marker.value,c.getSkyLight(p)];})())`);
  assert.deepEqual(JSON.parse(hydrated), [25, true, ids.stone, [1,2,3,4], 10, 'native-format', 15]);
  const elapsed = Date.now() - start; const mem = vm.runtime.computeMemoryUsage(); const memory = vm.dump(mem); mem.dispose();
  const invalid = evaluate(`JSON.stringify((()=>{const {Chunk,row}=fixture;return [
    {...row,data:row.data.slice(0,-4)}, {...row,data:row.data+'AAAA'}, {...row,worldHeight:17}, {...row,blockLight:[]},
    {...row,blockEntities:[{x:16,y:-64,z:0,nbt:{type:'compound',value:{}}}]},
    {...row,data:'AAAA'}, {...row,skyLight:Array(26).fill('AA==')}, ...fixture.badRows
  ].map(r=>{try{Chunk.fromSnapshot(r);return false}catch(e){return true}})})())`);
  assert.deepEqual(JSON.parse(invalid), Array(12).fill(true));
  console.log(JSON.stringify({ differential: 'passed', sourceCorrections: 6, nativeFormatRejections: 12, columns: 25, hydrationMs: elapsed, memoryUsed: memory.memory_used_size, bundleBytes: built.outputFiles[0].text.length, evidence: 'source-generated bytes, bundled QuickJS only; no Java/live conformance' }));
} finally { vm.dispose(); }
