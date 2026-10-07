// Authored before books.mjs. Library/orchestration fixtures, never native proof.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
const root = fileURLToPath(new URL('../..', import.meta.url));
const referenceRoot = resolve(process.env.MINEFLAYER_REFERENCE_ROOT ?? resolve(root, 'tools/mineflayer-reference'));
const pluginRoot = resolve(process.env.MINEFLAYER_PLUGIN_ROOT ?? resolve(root, 'bb-plugin'));
const reference = createRequire(resolve(referenceRoot, 'package.json'));
const plugin = createRequire(resolve(pluginRoot, 'package.json'));
assert.equal(reference('mineflayer/package.json').version, '4.39.0');
const registry = reference('minecraft-data')('1.21.1');
const Item = reference('prismarine-item')(registry), factory = reference('prismarine-windows')(registry);
const { EventEmitter } = reference('node:events');
const upstream = reference('mineflayer/lib/plugins/book');
const data = Object.fromEntries(['itemsArray', 'enchantmentsByName'].map(k => [k, registry[k]]));
const ids = Object.fromEntries(['writable_book', 'written_book', 'stone'].map(n => [n, registry.itemsByName[n].id]));

async function exercise(install, Item, factory, EventEmitter, registry, s, upstreamMode = false) {
  const bot = new EventEmitter(), events = [], sent = [], errors = [];
  bot.registry = registry; bot.supportFeature = () => false;
  bot.supportFeature = feature => ['hasEditBookPacket', 'itemsWithComponents'].includes(feature);
  bot.inventory = factory.createWindow(0, 'minecraft:inventory', 'Inventory');
  bot.currentWindow = s.container ? factory.createWindow(7, 'minecraft:generic_9x3', 'Chest') : null;
  bot.quickBarSlot = s.selected ?? 5;
  let selected = bot.quickBarSlot, generation = 1, tail = Promise.resolve(), ticket = 0, poison, busy = false, revision = 20;
  const native = new Map(), keys = new Map();
  const key = () => (++revision).toString(16).padStart(64, '0');
  function item(spec) {
    if (!spec) return null;
    const value = new Item(registry.itemsByName[spec.name ?? 'writable_book'].id, spec.count ?? 1, 0, spec.nbt);
    value.stackSize = spec.stackSize ?? value.stackSize;
    value.components = spec.components ?? [{ type: 'writable_book_content', data: { pages: [{ content: 'old' }] } }];
    value.componentMap = new Map(value.components.map(c => [c.type, c.data]));
    return value;
  }
  const source = s.slot ?? 9;
  for (const [slot, spec] of [[source, s.missing ? null : { ...s.book }], [36, { name: 'stone', count: 7, ...s.displaced }]]) {
    if (slot === 36 && source === 36) continue;
    native.set(slot, item(spec)); keys.set(slot, key());
  }
  if (source === 36) { native.set(36, item(s.missing ? null : { ...s.book })); keys.set(36, key()); }
  function copyItem(value) {
    if (!value) return null;
    const copy = new Item(value.type, value.count, value.metadata, JSON.parse(JSON.stringify(value.nbt)));
    copy.stackSize = value.stackSize;
    copy.components = JSON.parse(JSON.stringify(value.components));
    copy.componentMap = new Map(copy.components.map(c => [c.type, c.data]));
    return copy;
  }
  function hydrate() {
    for (let i = 0; i < bot.inventory.slots.length; i++) {
      const value = native.get(i) ?? null;
      bot.inventory.updateSlot(i, copyItem(value));
    }
  }
  hydrate();
  if (s.cursor) bot.inventory.selectedItem = item({ name: 'stone', count: 2 });
  const current = () => bot.currentWindow || bot.inventory;
  const nativeSlot = slot => slot >= 36 ? slot - 36 : slot < 9 ? 44 - slot : slot;
  const snapshot = () => ({ hands: { selected, menu: { id: current().id, generation,
    slots: Array.from({ length: 46 }, (_, slot) => ({ slot, inventorySlot: nativeSlot(slot), itemKey: native.get(slot) ? keys.get(slot) : null })) } } });
  const requireValue = (test, code, message) => { if (!test) throw Object.assign(new Error(message), { name: 'InventoryError', code }); };
  const assertReady = () => { if (poison) throw poison; };
  const capture = (window = current()) => ({ window, id: window.id, generation });
  const check = ctx => { assertReady(); requireValue(ctx.window === current() && ctx.generation === generation, 'WindowChanged', 'changed window'); return snapshot().hands.menu; };
  const isKnownActionError = e => e.known === true;
  function maybeFail(where) {
    if (s.fail !== where) return;
    if (s.failure === 'generation') { generation++; throw Object.assign(new Error('changed window'), { name: 'InventoryError', code: 'WindowChanged' }); }
    const e = Object.assign(new Error('injected ' + where), { known: s.failure === 'known' });
    if (!e.known) poison = e;
    throw e;
  }
  function exchange(a, b) {
    const old = native.get(a), oldKey = keys.get(a);
    native.set(a, native.get(b)); keys.set(a, keys.get(b));
    native.set(b, old); keys.set(b, oldKey); hydrate();
    events.push(['swap', Math.min(a, b), Math.max(a, b)]);
  }
  async function select(slot) { assertReady(); if (selected !== slot) { maybeFail('select'); selected = slot; bot.quickBarSlot = slot; events.push(['select', slot]); } }
  function edit(args) {
    const slot = 36 + args.slot, old = native.get(slot);
    requireValue(old?.name === 'writable_book', 'NoBook', 'no native book');
    requireValue(upstreamMode || args.expectedItemKey === keys.get(slot), 'ItemChanged', 'wrong native key');
    const signed = typeof args.title === 'string';
    const next = new Item(signed ? registry.itemsByName.written_book.id : old.type, old.count, 0, old.nbt);
    next.stackSize = old.stackSize;
    next.components = old.components.filter(c => c.type !== 'writable_book_content' && c.type !== 'written_book_content');
    next.components.push({ type: signed ? 'written_book_content' : 'writable_book_content', data: signed
      ? { title: args.title, author: '[TooManyAgents]', generation: 0, resolved: true, pages: [...args.pages] } : { pages: [...args.pages] } });
    next.componentMap = new Map(next.components.map(c => [c.type, c.data]));
    native.set(slot, next); keys.set(slot, key()); hydrate();
  }
  const io = {
    current, capture, check, requireValue, assertReady, snapshot, isKnownActionError,
    queueWindow(window, work) {
      const ctx = capture(window), n = ++ticket;
      const result = tail.then(async () => { check(ctx); requireValue(!busy, 'NestedQueue', 'queue reentered'); busy = true; try { return await work(ctx, n); } finally { busy = false; } });
      tail = result.catch(() => {}); return result;
    },
    async reserveCursor(ctx) { check(ctx); if (ctx.window.selectedItem) { events.push(['reserve']); ctx.window.selectedItem = null; } },
    async close(ctx) { check(ctx); events.push(['close']); bot.currentWindow = null; generation++; },
    select,
    async click(ctx, slot, button, mode) {
      check(ctx); requireValue(mode === 2 && button === 0, 'UnexpectedClick', 'book fixture expects native swap');
      maybeFail('swap'); exchange(slot, 36 + button);
    },
    async move(ctx, a, b) { check(ctx); exchange(a, b); },
    async send(args) {
      check(capture()); sent.push({ type: args.type, slot: args.slot, title: args.title ?? null, pages: args.pages });
      events.push(['edit', args.slot]);
      if (s.fail === 'edit' && s.failure === 'unknownAfterApply') edit(args);
      maybeFail('edit'); edit(args);
      if (s.fail === 'afterEdit') { generation++; }
    },
  };
  if (upstreamMode) {
    bot.setQuickBarSlot = slot => { selected = slot; if (bot.quickBarSlot !== slot) events.push(['select', slot]); bot.quickBarSlot = slot; };
    bot.moveSlotItem = async (a, b) => {
      // The real public Window click implementation exercises the exact source
      // moveSlotItem sequence. This is library geometry, not a native click model.
      bot.inventory.acceptClick({ slot: a, mouseButton: 0, mode: 0, item: bot.inventory.slots[a] });
      bot.inventory.acceptClick({ slot: b, mouseButton: 0, mode: 0, item: bot.inventory.slots[b] });
      if (bot.inventory.selectedItem) bot.inventory.acceptClick({ slot: a, mouseButton: 0, mode: 0, item: bot.inventory.slots[a] });
      native.set(a, copyItem(bot.inventory.slots[a])); native.set(b, copyItem(bot.inventory.slots[b]));
      keys.set(a, key()); keys.set(b, key()); events.push(['swap', Math.min(a, b), Math.max(a, b)]);
    };
    bot._syncWindow = async () => {};
    bot._client = { write(type, packet) {
      sent.push({ type: 'edit_book', slot: packet.hand, title: packet.title ?? null, pages: packet.pages }); events.push(['edit', packet.hand]);
      // Source installs its acknowledgement listener after write returns.
      Promise.resolve().then(() => edit({ slot: packet.hand, title: packet.title, pages: packet.pages }));
    } };
    install(bot);
  } else {
    bot.moveSlotItem = bot.closeWindow = bot.equip = () => { throw new Error('public nested queue call'); };
    install(bot, io);
  }
  const calls = s.calls ?? [{ method: s.sign ? 'signBook' : 'writeBook', slot: source, pages: s.pages ?? ['Hello\nworld', '😀 literal {"text":"book"}'], author: 'spoofed author', title: s.title ?? 'Native title' }];
  const invoke = async c => {
    try {
      const result = c.method === 'signBook' ? await bot.signBook(c.slot, c.pages, c.author, c.title) : await bot.writeBook(c.slot, c.pages);
      return result === undefined ? 'void' : result;
    } catch (e) { errors.push({ name: e.name, code: e.code, message: e.message }); return 'rejected'; }
  };
  let values;
  if (s.concurrent) values = await Promise.all(calls.map(invoke));
  else { values = []; for (const c of calls) values.push(await invoke(c)); }
  const slots = [...native].filter(([, value]) => value).sort((a, b) => a[0] - b[0]).map(([slot, value]) => ({ slot, name: value.name, count: value.count, nbt: value.nbt, components: value.components }));
  return { values, slots, selected, events, sent, errors, cursor: !!bot.inventory.selectedItem, open: !!bot.currentWindow };
}
const cases = [], defects = [], safety = [];
const add = (label, s) => cases.push({ label, s });
for (const slot of [9, 15, 35, 36, 40, 44]) for (const sign of [false, true]) add(`ordinary ${slot}/${sign}`, { slot, sign });
add('component metadata preserved', { sign: true, book: { nbt: { type: 'compound', value: { marker: { type: 'string', value: 'keep' } } }, components: [{ type: 'custom_name', data: { type: 'string', value: 'Keep name' } }, { type: 'custom_data', data: { type: 'compound', value: { marker: { type: 'int', value: 7 } } } }] } });
add('write then sign', { calls: [{ method: 'writeBook', slot: 9, pages: ['first'] }, { method: 'signBook', slot: 9, pages: ['second'], title: 'Final', author: 'Ignored' }] });
add('empty pages and title', { sign: true, pages: [], title: '' });
add('no-op content still completes', { calls: [{ method: 'writeBook', slot: 9, pages: ['same'] }, { method: 'writeBook', slot: 9, pages: ['same'] }] });
add('maximum writable page and page count', { pages: Array(100).fill('x'.repeat(1024)) });
defects.push({ label: 'compatible stack staging must not merge books', s: { book: { count: 2, stackSize: 64 }, displaced: { name: 'writable_book', count: 3, stackSize: 64 } } });
for (const failure of ['known', 'unknown', 'unknownAfterApply', 'generation']) safety.push({ label: `edit failure ${failure}`, s: { fail: 'edit', failure } });
safety.push({ label: 'generation after accepted edit', s: { fail: 'afterEdit' } });
safety.push({ label: 'unrelated window and cursor', s: { container: true, cursor: true } });
safety.push({ label: 'concurrent shared queue', s: { concurrent: true, calls: [{ method: 'writeBook', slot: 9, pages: ['first'] }, { method: 'signBook', slot: 9, pages: ['second'], title: 'Final', author: null }] } });
for (const call of [
  { slot: -1, pages: [] }, { slot: 45, pages: [] }, { slot: 9.5, pages: [] }, { slot: 9, pages: null }, { slot: 9, pages: [null] },
  { slot: 9, pages: Array(101).fill('') }, { slot: 9, pages: ['x'.repeat(1025)] },
  { method: 'signBook', slot: 9, pages: ['x'.repeat(8193)], title: 'X' }, { method: 'signBook', slot: 9, pages: [], title: 'x'.repeat(33) },
  { method: 'signBook', slot: 9, pages: [], title: null }, { method: 'signBook', slot: 9, pages: [], title: 7 },
]) safety.push({ label: 'invalid arguments', s: { calls: [{ method: 'writeBook', ...call }], invalid: true } });
safety.push({ label: 'nonbook', s: { book: { name: 'stone' }, invalid: true } });
safety.push({ label: 'missing book', s: { missing: true, invalid: true } });
const run = s => exercise(upstream, Item, factory, EventEmitter, registry, structuredClone(s), true);
const expected = [];
for (const { s } of cases) expected.push(await run(s));
assert(expected.every(r => r.values.every(v => v === 'void')), 'nonempty ordinary successful source scenarios');
for (const { s } of defects) { const r = await run(s); assert.equal(r.slots.find(i => i.slot === 9).count, 5); assert(!r.slots.some(i => i.slot === 36)); }
if (process.argv.includes('--reference-only')) console.log(`Reference ready: ${cases.length} real book-plugin scenarios; compatible-stack merge defect demonstrated; ${safety.length} preauthored guest safety cases.`);
else {
  const { build } = plugin('esbuild'), { getQuickJS } = plugin('quickjs-emscripten');
  const bundle = await build({ stdin: { contents: `import {installBooks} from './bb-plugin/scripting/books.mjs';import {createItemClass} from './bb-plugin/scripting/items.mjs';import {createWindowFactory} from './bb-plugin/scripting/windows.mjs';import {EventEmitter} from 'events';globalThis.Item=createItemClass(${JSON.stringify(data)});globalThis.factory=createWindowFactory(Item);globalThis.EventEmitter=EventEmitter;globalThis.registry={itemsByName:${JSON.stringify(Object.fromEntries(Object.entries(ids).map(([k,id])=>[k,{id}])))} };globalThis.install=installBooks;`, resolveDir: root }, bundle: true, write: false, platform: 'browser', format: 'iife', target: 'es2022', metafile: true, alias: { events: plugin.resolve('events/') }, nodePaths: [resolve(pluginRoot, 'node_modules')] });
  assert(Object.values(bundle.metafile.outputs).every(o => o.imports.length === 0));
  const vm = (await getQuickJS()).newContext(); vm.runtime.setMemoryLimit(64*1024*1024); vm.runtime.setMaxStackSize(512*1024); let deadline;
  vm.runtime.setInterruptHandler(() => Date.now() > deadline);
  function evaluate(code) { deadline=Date.now()+10000; const r=vm.evalCode(code), h=r.error??r.value, value=vm.dump(h);if(h.alive)h.dispose();if(r.error)throw new Error(JSON.stringify(value));return value; }
  function guest(s) {
    evaluate(`globalThis.result=undefined;exercise(install,Item,factory,EventEmitter,registry,${JSON.stringify(s)}).then(v=>{globalThis.result=JSON.stringify(v)},e=>{globalThis.result=JSON.stringify({uncaught:String(e)})});`);
    for(let i=0;i<10000;i++){const result=evaluate('globalThis.result');if(result!==undefined)return JSON.parse(result);const jobs=vm.runtime.executePendingJobs();if(jobs.error){const e=vm.dump(jobs.error);jobs.error.dispose();throw new Error(JSON.stringify(e));}}
    throw new Error('Guest promise failed to settle');
  }
  try {
    evaluate(bundle.outputFiles[0].text);evaluate(`globalThis.exercise=${exercise};`);
    for(const [i,{label,s}] of cases.entries()) assert.deepEqual(guest(s),expected[i],label);
    for(const {label,s} of defects){const r=guest(s);assert.deepEqual(r.values,['void'],label);assert.equal(r.slots.find(i=>i.slot===9).count,2);assert.equal(r.slots.find(i=>i.slot===36).count,3);}
    for(const {label,s} of safety){
      const r=guest(s);
      if(s.invalid){assert.deepEqual(r.values,['rejected'],label);assert.deepEqual(r.events,[],label);}
      else if(s.fail){assert.deepEqual(r.values,['rejected'],label);const at=r.events.findIndex(e=>e[0]==='edit');if(s.failure==='known'){assert.equal(r.selected,5);assert.equal(r.slots.find(i=>i.slot===9).name,'writable_book');}else assert.equal(r.events.length,at+1,label+' no cleanup after unknown/window replacement');}
      else {assert(r.values.every(v=>v==='void'),label);assert.equal(r.selected,5);assert(!r.open);assert(!r.cursor);}
    }
    console.log(`PASS: ${cases.length} real-source comparisons, compatible-stack correction, ${safety.length} queue/error/bounds scenarios in QuickJS64MiB/512KiB. Private-io fixture evidence only; native/live checks pending.`);
  } finally {vm.dispose();}
}
