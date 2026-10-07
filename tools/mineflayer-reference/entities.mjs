// Preimplementation differential scenarios for prismarine-entity 2.6.0.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
const root = fileURLToPath(new URL('../..', import.meta.url));
const referenceRoot = resolve(process.env.MINEFLAYER_REFERENCE_ROOT ?? resolve(root, 'tools/mineflayer-reference'));
const pluginRoot = resolve(process.env.MINEFLAYER_PLUGIN_ROOT ?? resolve(root, 'bb-plugin'));
const reference = createRequire(resolve(referenceRoot, 'package.json'));
const plugin = createRequire(resolve(pluginRoot, 'package.json'));
for (const [name, version] of Object.entries({ 'prismarine-entity': '2.6.0', 'prismarine-item': '1.18.0', 'prismarine-chat': '1.13.0', vec3: '0.1.10' })) assert.equal(reference(resolve(referenceRoot, 'node_modules', name, 'package.json')).version, version);
const registry = reference('minecraft-data')('1.21.1');
const Entity = reference('prismarine-entity')(registry);
const Item = reference('prismarine-item')(registry);
const { Vec3 } = reference('vec3');
const { EventEmitter } = reference('node:events');
const data = Object.fromEntries(['itemsArray', 'enchantmentsByName', 'language'].map(k => [k, registry[k]]));
function encode(v) {
  if (v === undefined) return ['undefined'];
  if (typeof v === 'function') return ['function'];
  if (typeof v === 'number' && !Number.isFinite(v)) return ['number', String(v)];
  if (v instanceof Map) return ['map', [...v].map(encode)];
  if (Array.isArray(v)) return Array.from(v, encode);
  if (v && typeof v === 'object') return Object.fromEntries(Object.keys(v).sort().map(k => [k, encode(v[k])]));
  return v;
}
function exercise(Entity, Item, Vec3, EventEmitter, s) {
  'use strict'; // Keep the serialized QuickJS scenario in the host module's strict mode.
  const traces = [], saved = console.trace;
  console.trace = (...args) => traces.push(args);
  const attempt = fn => { try { return fn(); } catch (e) { return { error: e.name, message: ['TypeError', 'RangeError'].includes(e.name) ? undefined : e.message }; } };
  try {
    return attempt(() => {
      if (s.kind === 'surface') return { prototype: Object.getOwnPropertyNames(Entity.prototype).sort(), arity: Entity.length,
        descriptors: Object.fromEntries(['mobType', 'objectType', 'heldItem'].map(k => { const d = Object.getOwnPropertyDescriptor(Entity.prototype, k); return [k, { get: !!d.get, set: !!d.set, enumerable: d.enumerable, configurable: d.configurable }]; })) };
      const e = new Entity(s.id);
      if (s.fields) Object.assign(e, s.fields);
      if (s.kind === 'equipment') {
        const items = s.entries.map(spec => spec === null ? null : new Item(spec.id, spec.count, spec.metadata));
        const returns = s.indices.map((slot, i) => e.setEquipment(slot, items[i]));
        const beforeHeld = e.heldItem;
        const readonly = attempt(() => { e.heldItem = items[0]; return 'assigned'; });
        return { slots: e.equipment, keys: Object.keys(e.equipment), length: e.equipment.length, returns,
          held: e.heldItem, same: e.heldItem === beforeHeld, identity: s.indices.map((slot, i) => e.equipment[slot] === items[i]), readonly };
      }
      if (s.kind === 'custom') {
        if (s.present) e.metadata[2] = s.value;
        const before = encode(e.metadata);
        const value = e.getCustomName();
        return { value: value === null ? null : { fields: value, string: value.toString(), motd: value.toMotd(), className: value.constructor.name }, before, after: encode(e.metadata) };
      }
      if (s.kind === 'dropped') {
        e.name = s.name;
        if (s.present) e.metadata[s.index ?? 8] = s.value;
        const before = encode(e.metadata);
        const item = e.getDroppedItem();
        const again = e.getDroppedItem();
        return { item, fresh: item === null ? again === null : item !== again, before, after: encode(e.metadata),
          behavior: item === null ? null : { className: item.constructor.name, durabilityUsed: item.durabilityUsed, enchants: item.enchants, customName: item.customName, customLore: item.customLore, slot: Item.toNotch(item) } };
      }
      if (s.kind === 'aliases') {
        const before = [e.mobType, e.objectType];
        e.mobType = 'Zombie'; const middle = [e.mobType, e.objectType, e.displayName];
        e.objectType = 'Item'; return { before, middle, after: [e.mobType, e.objectType, e.displayName], traces };
      }
      if (s.kind === 'events') {
        const log = [];
        function a(n) { log.push(['a', n, this === e]); }
        function b(n) { log.push(['b', n, this === e]); }
        const chain = [e.on('move', a) === e, e.once('move', b) === e, e.prependListener('move', n => log.push(['first', n])) === e];
        const before = [e.listenerCount('move'), e.eventNames(), e.listeners('move').includes(b), e.rawListeners('move').length];
        const emitted = [e.emit('move', 1), e.emit('move', 2), e.emit('missing')];
        e.off('move', a); e.removeAllListeners('move'); e.setMaxListeners(12);
        const symbolic = Symbol('event'); e.prependOnceListener(symbolic, a); e.emit(symbolic, 3); e.emit(symbolic, 4);
        const error = attempt(() => e.emit('error', new Error('explicit entity error')));
        return { chain, before, emitted, log, after: [e.listenerCount('move'), e.eventNames(), e.getMaxListeners()], error, emitter: e instanceof EventEmitter };
      }
      const other = new Entity(2);
      e.position.x = s.x ?? 0;
      return { fields: Object.fromEntries(Object.entries(e).filter(([k]) => !k.startsWith('_'))), equipmentKeys: Object.keys(e.equipment),
        typed: [e instanceof Entity, e instanceof EventEmitter, e.position instanceof Vec3, e.velocity instanceof Vec3],
        independent: ['position', 'velocity', 'effects', 'equipment', 'metadata', 'passengers'].every(k => e[k] !== other[k]),
        geometry: e.position.plus(new Vec3(1, 2, 3)), held: e.heldItem, custom: e.getCustomName(), dropped: e.getDroppedItem() };
    });
  } finally { console.trace = saved; }
}
const cases = [], add = (label, s) => cases.push({ label, s });
const id = name => registry.itemsByName[name].id;
const slot = (name, count = 1, components = []) => ({ itemId: id(name), itemCount: count, addedComponentCount: components.length, removedComponentCount: 0, components, removeComponents: [] });
add('surface', { kind: 'surface' });
for (const eid of [undefined, null, 0, 1, -1, '17', 2147483647]) add(`constructor ${eid}`, { id: eid, x: 1.5 });
add('public observed fields remain ordinary assigned properties', { id: 10, fields: { type: 'player', uuid: 'uuid', username: 'Alex', displayName: 'Alex', entityType: 128, kind: 'Players', name: 'player', count: 1, health: 17, food: 18, foodSaturation: 3, elytraFlying: false, noClip: false, player: { username: 'Alex' } } });
add('deprecated aliases', { kind: 'aliases' });
add('EventEmitter public behavior', { kind: 'events' });
for (const indices of [[0], [0, 1, 2, 3, 4, 5], [5], [0, 0], [-1], [7], ['head']]) add(`equipment ${indices}`, { kind: 'equipment', indices, entries: indices.map((_, i) => ({ id: id(i ? 'iron_helmet' : 'diamond_sword'), count: 1, metadata: 0 })) });
add('equipment null clearing', { kind: 'equipment', indices: [0, 0], entries: [{ id: id('stone'), count: 3 }, null] });
for (const value of [undefined, null, '', 'Raw', '{"text":"Name","color":"gold"}', { type: 'end' }, { type: 'string', value: 'NBT 🐈' }, { type: 'compound', value: { text: { type: 'string', value: 'Named' }, bold: { type: 'byte', value: 1 }, extra: { type: 'list', value: { type: 'compound', value: [{ text: { type: 'string', value: ' suffix' } }] } } } }, { type: 'compound', value: { translate: { type: 'string', value: 'entity.minecraft.zombie' } } }, { type: 'list', value: { type: 'string', value: ['A', 'B'] } }, { type: 'list', value: null }]) add(`custom name ${JSON.stringify(value)}`, { kind: 'custom', present: true, value });
add('absent custom name', { kind: 'custom' });
for (const name of ['item', 'Item', 'item_stack', 'ITEM', 'zombie', undefined]) for (const value of [undefined, null, { itemCount: 0 }, slot('diamond', 3), slot('diamond_pickaxe', 1, [{ type: 'damage', data: 3 }, { type: 'enchantments', data: { enchantments: [{ id: 15, level: 3 }], showTooltip: true } }])]) add(`drop name ${name} slot ${JSON.stringify(value)}`, { kind: 'dropped', name, present: true, value });
add('wrong metadata index remains missing', { kind: 'dropped', name: 'item', index: 7, present: true, value: slot('stone') });
for (const item of registry.itemsArray) add(`dropped registry ${item.name}`, { kind: 'dropped', name: 'item', present: true, value: slot(item.name, Math.min(3, item.stackSize)) });
add('dropped custom component name/lore', { kind: 'dropped', name: 'item', present: true, value: slot('written_book', 1, [{ type: 'custom_name', data: { type: 'string', value: 'Custom' } }, { type: 'lore', data: [{ type: 'string', value: 'Line' }] }]) });
const expected = cases.map(({ s }) => encode(exercise(Entity, Item, Vec3, EventEmitter, structuredClone(s))));
assert(expected.some(v => v.item?.name === 'diamond' && v.item.count === 3), 'nonempty decoded item fixture');
assert(expected.some(v => v.value?.string === 'Named suffix'), 'nonempty decoded name fixture');
if (process.argv.includes('--reference-only')) console.log(`Reference ready: ${cases.length} Entity scenarios. Guest not loaded.`);
else {
  const { build } = plugin('esbuild'); const { getQuickJS } = plugin('quickjs-emscripten');
  const bundle = await build({ stdin: { contents: `import {createEntityClass} from './bb-plugin/scripting/entities.mjs';import {createItemClass} from './bb-plugin/scripting/items.mjs';import {createChatMessageClass} from './bb-plugin/scripting/chat.mjs';import {Vec3} from 'vec3';import {EventEmitter} from 'events';const registry=${JSON.stringify(data)};globalThis.Item=createItemClass(registry);globalThis.ChatMessage=createChatMessageClass(registry);globalThis.Entity=createEntityClass(registry,{Item,ChatMessage});globalThis.Vec3=Vec3;globalThis.EventEmitter=EventEmitter;`, resolveDir: root }, bundle: true, write: false, platform: 'browser', format: 'iife', target: 'es2022', keepNames: true, metafile: true, nodePaths: [resolve(referenceRoot, 'node_modules'), resolve(pluginRoot, 'node_modules')] });
  assert(Object.values(bundle.metafile.outputs).every(o => o.imports.length === 0));
  const vm = (await getQuickJS()).newContext(); vm.runtime.setMemoryLimit(64 * 1024 * 1024); vm.runtime.setMaxStackSize(512 * 1024); let deadline;
  vm.runtime.setInterruptHandler(() => Date.now() > deadline);
  const evaluate = code => { deadline = Date.now() + 10000; const result = vm.evalCode(code), h = result.error ?? result.value; const v = vm.dump(h); h.dispose(); if (result.error) throw new Error(JSON.stringify(v)); return v; };
  const literal = v => v === undefined ? 'undefined' : Array.isArray(v) ? `[${v.map(literal).join(',')}]` : v && typeof v === 'object' ? `{${Object.entries(v).map(([k, x]) => `${JSON.stringify(k)}:${literal(x)}`).join(',')}}` : JSON.stringify(v);
  try {
    evaluate('globalThis.console={trace(){},warn(){}};'); evaluate(bundle.outputFiles[0].text); evaluate(`globalThis.encode=${encode};globalThis.exercise=${exercise};`);
    for (let start = 0; start < cases.length; start += 50) {
      const batch = cases.slice(start, start + 50); const actual = JSON.parse(evaluate(`JSON.stringify(${literal(batch.map(c => c.s))}.map(s=>encode(exercise(Entity,Item,Vec3,EventEmitter,s))))`));
      batch.forEach(({ label }, i) => assert.deepEqual(actual[i], expected[start + i], label));
    }
    // Class injection must preserve identity with the already exposed guest types.
    assert.equal(evaluate(`(()=>{const e=new Entity(1); e.name='item';e.metadata[8]=${JSON.stringify(slot('diamond', 3))};e.metadata[2]={type:'string',value:'name'};return e.getDroppedItem() instanceof Item && e.getCustomName() instanceof ChatMessage;})()`), true);
    console.log(`PASS: ${cases.length} Entity comparisons, all ${registry.itemsArray.length} dropped item IDs, injected class identity in bundled QuickJS at 64 MiB. No native hydration/events claimed.`);
  } finally { vm.dispose(); }
}
