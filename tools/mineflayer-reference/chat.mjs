// Differential scenarios authored before chat.mjs. Source/library evidence only.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
const root = fileURLToPath(new URL('../..', import.meta.url));
const referenceRoot = resolve(process.env.MINEFLAYER_REFERENCE_ROOT ?? resolve(root, 'tools/mineflayer-reference'));
const pluginRoot = resolve(process.env.MINEFLAYER_PLUGIN_ROOT ?? resolve(root, 'bb-plugin'));
const reference = createRequire(resolve(referenceRoot, 'package.json'));
const plugin = createRequire(resolve(pluginRoot, 'package.json'));
for (const [name, version] of Object.entries({ 'prismarine-chat': '1.13.0', mojangson: '2.1.0', nearley: '2.20.1', 'prismarine-nbt': '2.8.0' })) {
  assert.equal(reference(resolve(referenceRoot, 'node_modules', name, 'package.json')).version, version, name);
}
const registry = reference('minecraft-data')('1.21.1');
registry.chatFormattingById = {
  0: { formatString: 'chat.type.text', parameters: ['sender', 'content'] },
  3: { formatString: 'chat.type.team.text', parameters: ['target', 'sender', 'content'] },
  7: { formatString: '%2$s / %1$s / %% / %s / %9$s', parameters: ['content', 'sender'] },
};
const ChatMessage = reference('prismarine-chat')(registry);
const processNbtMessage = reference('prismarine-chat').processNbtMessage;
const data = { language: registry.language, chatFormattingById: registry.chatFormattingById };
function encode(value) {
  if (value === undefined) return ['undefined'];
  if (typeof value === 'function') return ['function'];
  if (typeof value === 'bigint') return ['bigint', String(value)];
  if (typeof value === 'number' && !Number.isFinite(value)) return ['number', String(value)];
  if (Array.isArray(value)) return Array.from(value, encode);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(k => [k, encode(value[k])]));
  return value;
}
function exercise(ChatMessage, processNbtMessage, s) {
  const warnings = [];
  const saved = console.warn;
  console.warn = (...args) => warnings.push(args);
  const attempt = fn => {
    try { return fn(); } catch (e) { return { error: e.name, message: ['TypeError', 'RangeError', 'SyntaxError', 'InternalError'].includes(e.name) ? undefined : e.message }; }
  };
  const describe = message => ({ fields: encode(message), string: attempt(() => message.toString(s.lang)),
    motd: attempt(() => message.toMotd(s.lang, s.parent)), ansi: attempt(() => message.toAnsi(s.lang, s.codes)),
    html: attempt(() => message.toHTML(s.lang, s.styles, s.formats)), value: attempt(() => message.valueOf()),
    length: attempt(() => message.length()), parts: [undefined, -1, 0, 1, 2, 99, '0'].map(i => attempt(() => message.getText(i, s.lang))),
    instance: message instanceof ChatMessage, childInstances: !message.extra || message.extra.every(m => m instanceof ChatMessage),
    afterRender: encode(message) });
  try {
    return attempt(() => {
      if (s.kind === 'nbt') return processNbtMessage(s.input);
      if (s.kind === 'surface') return { methods: Object.getOwnPropertyNames(ChatMessage.prototype).sort(), statics: Object.getOwnPropertyNames(ChatMessage).filter(n => !['length', 'name', 'prototype'].includes(n)).sort(),
        builderMethods: Object.getOwnPropertyNames(ChatMessage.MessageBuilder.prototype).sort(), builderStatics: Object.getOwnPropertyNames(ChatMessage.MessageBuilder).filter(n => !['length', 'name', 'prototype'].includes(n)).sort() };
      let message;
      if (s.kind === 'network') message = ChatMessage.fromNetwork(s.type, s.params);
      else if (s.kind === 'notch') message = ChatMessage.fromNotch(s.input);
      else if (s.kind === 'builder' || s.kind === 'builderString') {
        const B = ChatMessage.MessageBuilder;
        const b = s.kind === 'builderString' ? B.fromString(s.input, s.options) : new B();
        const results = [];
        for (const [method, ...args] of s.calls ?? []) {
          const converted = args.map(arg => arg && arg.builder ? new B().setText(arg.builder) : arg);
          results.push(attempt(() => { const r = b[method](...converted); return r === b ? 'self' : r; }));
        }
        const result = { fields: encode(b), json: attempt(() => b.toJSON()), serialized: attempt(() => b.toString()), results,
          chat: attempt(() => describe(new ChatMessage(b))) };
        result.warnings = warnings;
        return result;
      } else message = new ChatMessage(s.input, s.warning);
      if (s.append) message.append(...s.append.map(v => v && v.chat ? new ChatMessage(v.chat) : v));
      if (s.reparse) { message.json = s.reparse; message.parse(); }
      const result = describe(message);
      result.clone = attempt(() => { const clone = message.clone(); return { fields: encode(clone), string: clone.toString(), distinct: clone !== message && clone.json !== message.json }; });
      result.warnings = warnings;
      return result;
    });
  } finally { console.warn = saved; }
}
const cases = [];
const add = (label, s) => cases.push({ label, s });
add('public surface', { kind: 'surface' });
for (const input of [undefined, null, false, true, 0, 1, -3.25, NaN, Infinity, '', 'plain', '§aGreen §lbold §rreset', 'Unicode 🐈 中文\u0000!', [], ['one', { text: 'two', bold: true }], {}, { '': 2.718281 }, { '': 'empty key' }, { '': {} },
  { text: '<tag>&\"\'é' }, { text: 0 }, { text: 42 }, { text: false }, { selector: '@p' }, { keybind: 'key.inventory' }, { score: { name: 'Alex', objective: 'kills', value: '12' } },
  { score: { name: 'Alex' } }, { translate: 'missing.key' }, { translate: 'missing.key', fallback: 'Hi %1$s: %s/%%', with: ['Ada'] }, { translate: 'constructor' },
  { translate: 'chat.type.text', with: ['Alex', { text: 'hello', color: 'gold' }] }, { translate: 'chat.type.text', with: null }, { translate: 'chat.type.text', with: {} }, { extra: null }, { extra: {} },
  { text: 'parent', extra: [{ text: 'child', bold: false }, { text: 'tail', color: 'blue' }], bold: true },
  { text: 'click', clickEvent: { action: 'run_command', value: '/say hi' } }, { clickEvent: {} }, { clickEvent: null }, { hoverEvent: null }, { hoverEvent: {} },
  { hoverEvent: { action: 'show_text', contents: { text: 'hint' } } }, { text: 'hello', font: 'minecraft:default', insertion: 'insert' }]) add(`constructor ${JSON.stringify(input)}`, { input });
for (const color of ['black', 'dark_blue', 'dark_green', 'dark_aqua', 'dark_red', 'dark_purple', 'gold', 'gray', 'dark_gray', 'blue', 'green', 'aqua', 'red', 'light_purple', 'yellow', 'white', 'bold', 'italic', 'obfuscated', 'strikethrough', 'underlined', 'reset', '#12abEF', '#invalid', 'unknown', 2]) {
  for (const bold of [undefined, false, true, 'false']) add(`color ${color} bold ${bold}`, { input: { text: 'styled', color, bold, extra: [{ text: 'child' }] }, warning: true });
}
for (const translate of ['chat.type.text', 'chat.type.announcement', 'chat.type.team.text', 'commands.give.success.single', 'death.attack.player', 'multiplayer.player.joined', 'commands.tp.success.location.single']) {
  add(`language ${translate}`, { input: { translate, with: ['Player', 'Stone', 3, 1.25] } });
}
for (const format of ['%s %s', '%2$s %1$s %2$s', '%% %s %d %0$s %9$s', '<&> %s', '', '%100000000000$s']) add(`custom format ${format}`, { input: { translate: 'test', with: ['one', { text: 'two', italic: true }] }, lang: { test: format } });
add('custom ANSI mapping', { input: { text: 'Hi', color: 'red', bold: true }, codes: { '§c': '[red]', '§l': '[bold]', '§r': '[reset]' } });
add('custom HTML style/format subset', { input: { text: 'Hi', color: 'red', bold: true }, styles: { red: 'color:tomato', bold: 'font-weight:bold' }, formats: ['color'] });
add('explicit MOTD parent', { input: { text: 'Hi', bold: false }, parent: { color: 'green', bold: true } });
add('append clone divergence retained', { input: 'base', append: ['tail', { chat: { text: 'typed' } }, 3, []] });
add('append raw object behavior', { input: 'base', append: [{ text: 'raw' }] });
add('reparse source properties persist', { input: { text: 'before' }, reparse: { translate: 'chat.type.text', with: ['A', 'B'] } });
for (const depth of [1, 8, 9, 12]) {
  let input = { text: 'end' };
  for (let i = 0; i < depth; i++) input = { text: `${i}:`, extra: [input] };
  add(`depth ${depth}`, { input });
}
add('text length boundary', { input: { text: 'A'.repeat(4100) } });
add('HTML length fallback escapes text', { input: { text: '<>'.repeat(1000), bold: true } });
const tag = (type, value) => ({ type, value });
const comp = value => tag('compound', value);
const nbtCases = [undefined, null, tag('end'), tag('string', 'plain'), tag('string', '§cRed'),
  comp({ text: tag('string', 'NBT name'), bold: tag('byte', 1), color: tag('string', 'gold') }),
  comp({ translate: tag('string', 'chat.type.text'), with: tag('list', { type: 'string', value: ['Alex', 'Hello'] }) }),
  comp({ text: tag('string', 'outer'), extra: tag('list', { type: 'compound', value: [{ text: tag('string', 'inner'), italic: tag('byte', 1) }] }) }),
  comp({ hoverEvent: comp({ action: tag('string', 'show_entity'), contents: comp({ id: tag('intArray', [-1, -2147483648, 2147483647, 1]), name: tag('string', 'Alex') }) }) }),
  comp({ nums: tag('list', { type: 'list', value: [{ type: 'int', value: [1, 2] }] }), long: tag('long', [0, 1]), array: tag('byteArray', [1, -1]) }),
  tag('list', { type: 'compound', value: [{ text: tag('string', 'list root') }] }), tag('list', { type: 'string', value: null }), comp({ bad: undefined }),
  ...[[], [1], [1, 2, 3, 4, 5], [2147483648], [NaN, 0, 0, 0]].map(a => comp({ id: tag('intArray', a) }))];
for (const [i, input] of nbtCases.entries()) {
  add(`NBT simplify ${i}`, { kind: 'nbt', input });
  add(`NBT fromNotch ${i}`, { kind: 'notch', input });
}
for (const input of ['{"text":"JSON"}', '"quoted"', 'null', 'true', '7', '["A",{"text":"B"}]', '{broken', { text: 'object' }]) add(`Notch ${JSON.stringify(input)}`, { kind: 'notch', input });
for (const content of ['{id:"minecraft:stone",Count:2b}', '{id:"minecraft:diamond_sword",tag:{display:{Name:"Sword"},Enchantments:[{id:"sharpness",lvl:3s}]}}', '{b:1b,s:-2s,i:3,l:4l,f:1.25f,d:2.5d,a:[I;1,2],list:["a","b"]}', '{unclosed:', '', 'nonsense']) {
  for (const value of [content, { text: content }, [content], [{ text: content }]]) add(`hover SNBT ${JSON.stringify(value)}`, { input: { text: 'item', hoverEvent: { action: 'show_item', value } } });
}
for (const type of [0, 3, 7, -1, 'toString']) for (const params of [{ sender: 'Alex', content: 'hello', target: 'Team' }, { sender: { text: 'Alex', color: 'gold' } }, {}]) add(`network ${type}/${JSON.stringify(params)}`, { kind: 'network', type, params });
for (const input of ['', 'plain', '&aGreen&lBold&rReset', '&xUnknown', 'trailing&', '§bA§rB', '§#123456hex', 'emoji🐈']) for (const options of [undefined, { colorSeparator: '§' }]) add(`builder string ${input}/${JSON.stringify(options)}`, { kind: 'builderString', input, options });
add('all builder setters chain and priority', { kind: 'builder', calls: [['setBold', true], ['setItalic', false], ['setUnderlined', true], ['setStrikethrough', true], ['setObfuscated', false], ['setColor', 'red'], ['setFont', 'minecraft:alt'], ['setInsertion', 'text'], ['setSelector', '@p'], ['setKeybind', 'key.jump'], ['setScore', 'A', 'kills'], ['setText', 'winner'], ['setClickEvent', 'copy_to_clipboard', 'copy'], ['addExtra', 'tail', { builder: 'child' }], ['setTranslate', 'chat.type.text'], ['addWith', 'name', { builder: 'body' }]] });
add('builder reset', { kind: 'builder', calls: [['setColor', 'red'], ['setBold', true], ['resetFormatting'], ['setText', 'reset']] });
for (const calls of [[['setFont']], [['setSelector', '@p']], [['setKeybind', 'key.inventory']], [['setScore', 'P', 'kills']], [['addExtra', {}]], [['addWith', null]]]) add(`builder edge ${JSON.stringify(calls)}`, { kind: 'builder', calls });
for (const type of ['contents', 'value', 'unexpected']) for (const action of ['show_item', 'show_entity', 'show_text', 'invalid']) {
  const data = action === 'show_item' ? { name: 'diamond_sword', count: 2, nbt: comp({ display: comp({ Name: tag('string', 'Sword') }), Damage: tag('int', 3) }) }
    : action === 'show_entity' ? { displayName: 'Alex', name: 'player', uuid: '12345678-1234-5678-1234-567812345678' } : { builder: 'tooltip' };
  add(`builder hover ${action}/${type}`, { kind: 'builder', calls: [['setText', 'hover'], ['setHoverEvent', action, data, type]] });
}
const expected = cases.map(({ s }) => encode(exercise(ChatMessage, processNbtMessage, structuredClone(s))));
// Declaration gap: MessageBuilder.fromNetwork is declared, but absent in source.
// Its intended component fields are independently provided by ChatMessage.fromNetwork.
const networkBuilderCases = [
  { type: 0, params: { sender: 'Alex', content: { text: 'hello', bold: true } } },
  { type: 3, params: { target: 'Team', content: 'body' } },
];
assert.equal(ChatMessage.MessageBuilder.fromNetwork, undefined);
const builderExpected = networkBuilderCases.map(s => ChatMessage.fromNetwork(s.type, s.params).json);
if (process.argv.includes('--reference-only')) console.log(`Reference ready: ${cases.length} chat scenarios; ${networkBuilderCases.length} declared builder network fixtures. Guest not loaded.`);
else {
  const { build } = plugin('esbuild');
  const { getQuickJS } = plugin('quickjs-emscripten');
  const bundle = await build({ stdin: { contents: `import {createChatMessageClass,processNbtMessage} from './bb-plugin/scripting/chat.mjs'; globalThis.ChatMessage=createChatMessageClass(${JSON.stringify(data)}); globalThis.processNbtMessage=processNbtMessage;`, resolveDir: root }, bundle: true, write: false, platform: 'browser', format: 'iife', target: 'es2022', metafile: true, nodePaths: [resolve(referenceRoot, 'node_modules'), resolve(pluginRoot, 'node_modules')] });
  assert(Object.values(bundle.metafile.outputs).every(o => o.imports.length === 0), 'self-contained browser bundle');
  assert(Object.keys(bundle.metafile.inputs).every(p => !p.includes('prismarine-nbt') && !p.includes('debug/')), 'no full host NBT/debug dependency');
  const vm = (await getQuickJS()).newContext(); vm.runtime.setMemoryLimit(64 * 1024 * 1024); vm.runtime.setMaxStackSize(512 * 1024);
  let deadline;
  vm.runtime.setInterruptHandler(() => Date.now() > deadline);
  const evaluate = (code, timeout = 10000) => {
    deadline = Date.now() + timeout;
    const result = vm.evalCode(code), h = result.error ?? result.value; const value = vm.dump(h); h.dispose();
    if (result.error) throw new Error(JSON.stringify(value)); return value;
  };
  const literal = v => v === undefined ? 'undefined' : typeof v === 'number' && !Number.isFinite(v) ? String(v)
    : Array.isArray(v) ? `[${v.map(literal).join(',')}]` : v && typeof v === 'object' ? `{${Object.entries(v).map(([k, x]) => `${JSON.stringify(k)}:${literal(x)}`).join(',')}}` : JSON.stringify(v);
  try {
    evaluate('globalThis.console={warn(){}};'); evaluate(bundle.outputFiles[0].text);
    evaluate(`globalThis.encode=${encode};globalThis.exercise=${exercise};`);
    for (let start = 0; start < cases.length; start += 25) {
      const batch = cases.slice(start, start + 25);
      const results = JSON.parse(evaluate(`JSON.stringify(${literal(batch.map(c => c.s))}.map(s=>encode(exercise(ChatMessage,processNbtMessage,s))))`));
      batch.forEach(({ label, s }, i) => {
        if (s.kind === 'surface') results[i].builderStatics = results[i].builderStatics.filter(k => k !== 'fromNetwork');
        assert.deepEqual(results[i], expected[start + i], label);
      });
    }
    for (const [i, s] of networkBuilderCases.entries()) assert.deepEqual(JSON.parse(evaluate(`JSON.stringify(ChatMessage.MessageBuilder.fromNetwork(${s.type},${literal(s.params)}).toJSON())`)), builderExpected[i]);
    // Potentially expensive parser input runs only in the bounded VM, never the
    // reference host. Completion or VM interruption are both safe outcomes.
    const resourceResults = [];
    for (const [name, content] of [['wide compound', '{a:[' + Array(1000).fill('1').join(',') + ']}'], ['deep malformed', '['.repeat(2000)]]) {
      const before = Date.now();
      try { evaluate(`new ChatMessage({hoverEvent:{action:'show_item',value:${JSON.stringify(content)}}}).toString()`, 250); resourceResults.push({ name, outcome: 'completed', ms: Date.now() - before }); }
      catch (e) { assert.match(e.message, /interrupted|out of memory|stack overflow/i); resourceResults.push({ name, outcome: 'bounded VM error', ms: Date.now() - before }); }
    }
    console.log(JSON.stringify({ pass: cases.length, builderNetwork: networkBuilderCases.length, bundleBytes: bundle.outputFiles[0].contents.length, bundleInputs: Object.keys(bundle.metafile.inputs), memoryMiB: 64, resourceResults, scope: 'Pure library comparison, no native chat/event evidence' }, null, 2));
  } finally { vm.dispose(); }
}
