// Read-only discovery. This command never loads a client, starts a server, or
// executes a scenario. Generated files live under the ignored run directory.
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const directory = dirname(fileURLToPath(import.meta.url));
const root = resolve(directory, '../..');
const referenceRoot = resolve(process.env.MINEFLAYER_REFERENCE_ROOT ?? directory);
const pluginRoot = resolve(process.env.MINEFLAYER_PLUGIN_ROOT ?? join(root, 'bb-plugin'));
const sourceRoot = resolve(process.env.MINEFLAYER_SOURCE_ROOT ?? root);
const require = createRequire(join(referenceRoot, 'package.json'));
const ts = require('typescript');
const coverage = JSON.parse(await readFile(join(directory, 'coverage.json'), 'utf8'));
const pins = JSON.parse(await readFile(join(directory, 'upstream.json'), 'utf8'));
const sha = text => createHash('sha256').update(text).digest('hex');
const packages = new Map(), inputs = new Map(), declarations = new Map();
const documents = [], inheritance = [], runtimeInheritance = [], imports = [], privateMembers = [], sourceFindings = [];
const legacyKeys = new Set();

for (const [name, pin] of Object.entries({ mineflayer: pins.mineflayer, 'mineflayer-pathfinder': pins.pathfinder, ...pins.dependencies })) {
  let location, manifest;
  for (const base of [referenceRoot, pluginRoot]) {
    try {
      location = join(base, 'node_modules', name);
      manifest = JSON.parse(await readFile(join(location, 'package.json'), 'utf8'));
      break;
    } catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  if (!manifest) throw new Error(`Missing pinned package ${name}`);
  if (manifest.version !== pin.version) throw new Error(`Expected ${name} ${pin.version}, found ${manifest.version}`);
  packages.set(name, { name, root: location, version: pin.version, commit: pin.commit });
}

async function source(name, path, kind) {
  const text = await readFile(join(packages.get(name).root, path), 'utf8');
  const id = `${name}/${path}`;
  inputs.set(id, { id, package: name, version: packages.get(name).version, path, sha256: sha(text) });
  return { name, path, text, id, kind, file: ts.createSourceFile(path, text, ts.ScriptTarget.Latest, true, path.endsWith('.js') ? ts.ScriptKind.JS : ts.ScriptKind.TS) };
}
function location(input, node) {
  return { input: input.id, line: input.file.getLineAndCharacterOfPosition(node.getStart(input.file)).line + 1, kind: input.kind };
}
function put(key, kind, input, node, extra = {}) {
  let entry = declarations.get(key);
  if (!entry) {
    entry = { key, kind, category: 'data-shape', applicability: 'pending-review', status: 'pending', signatures: [], sources: [] };
    declarations.set(key, entry);
  }
  if (input && node) {
    const at = location(input, node);
    if (!entry.sources.some(s => s.input === at.input && s.line === at.line && s.kind === at.kind)) entry.sources.push(at);
  }
  Object.assign(entry, extra);
  return entry;
}
const memberName = node => ts.isConstructorDeclaration(node) ? 'constructor' : node.name?.getText().replace(/^['"]|['"]$/g, '');
const has = (node, token) => node.modifiers?.some(m => m.kind === token);
const runtimeInterfaces = new Set(['Bot', 'Pathfinder', 'Movements', 'creativeMethods', 'WindowsExports', 'TypedEventEmitter', 'RegistryPc', 'IndexedData']);
const reviewPackages = new Set(['prismarine-chunk', 'prismarine-registry', 'minecraft-data', 'prismarine-nbt']);
const aliases = { EventEmitter: 'events.EventEmitter', TypedEmitter: 'typed-emitter.TypedEventEmitter' };

// Retain the original declaration key algorithm, including module augmentation
// keys. Types/options remain searchable data-shapes, not extra gameplay promises.
async function scanDeclarations(name, path, legacy = false) {
  const input = await source(name, path, 'declaration');
  const imported = {};
  for (const statement of input.file.statements) {
    if (!ts.isImportDeclaration(statement) || !ts.isStringLiteral(statement.moduleSpecifier)) continue;
    const module = statement.moduleSpecifier.text;
    imports.push({ package: name, module, ...location(input, statement) });
    const clause = statement.importClause;
    if (clause?.name) imported[clause.name.text] = module;
    if (clause?.namedBindings && ts.isNamedImports(clause.namedBindings)) {
      for (const e of clause.namedBindings.elements) imported[e.name.text] = `${module}.${e.propertyName?.text ?? e.name.text}`;
    }
  }
  function visit(node, prefix) {
    if (ts.isModuleDeclaration(node)) {
      const next = ts.isStringLiteral(node.name) ? node.name.text : `${prefix}.${node.name.text}`;
      if (node.body) visit(node.body, next);
    } else if (ts.isInterfaceDeclaration(node) || ts.isClassDeclaration(node)) {
      const key = `${prefix}.${node.name.text}`;
      const events = /Events$/.test(node.name.text);
      const runtime = ts.isClassDeclaration(node) || runtimeInterfaces.has(node.name.text);
      const applicability = runtime && !reviewPackages.has(name) ? 'applicable' : 'pending-review';
      put(key, ts.isInterfaceDeclaration(node) ? 'interface' : 'class', input, node, { category: events ? 'event-map' : runtime ? 'object' : 'data-shape', applicability });
      if (legacy) legacyKeys.add(key);
      for (const clause of node.heritageClauses ?? []) {
        if (clause.token !== ts.SyntaxKind.ExtendsKeyword) continue;
        for (const base of clause.types) inheritance.push({ derived: key, expression: base.expression.getText(input.file), prefix, package: name, imported, source: location(input, base) });
      }
      for (const member of node.members) {
        const memberKey = memberName(member);
        if (!memberKey) continue;
        if (has(member, ts.SyntaxKind.PrivateKeyword) || has(member, ts.SyntaxKind.ProtectedKeyword)) {
          privateMembers.push({ key: `${key}.${memberKey}`, reason: 'Explicit declaration visibility', ...location(input, member) });
          continue;
        }
        const full = `${key}.${memberKey}`;
        const entry = put(full, 'member', input, member, {
          owner: key, category: events ? 'event' : runtime ? 'member' : 'data-shape', applicability: events ? 'applicable' : applicability,
          binding: has(member, ts.SyntaxKind.StaticKeyword) ? 'static' : 'instance',
        });
        const signature = member.getText(input.file);
        if (!entry.signatures.includes(signature)) entry.signatures.push(signature);
        if (legacy) legacyKeys.add(full);
      }
    } else if (ts.isFunctionDeclaration(node) && node.name) {
      const key = `${prefix}.${node.name.text}`;
      const entry = put(key, 'function', input, node, { category: 'factory', applicability: 'pending-review' });
      entry.signatures.push(node.getText(input.file));
      if (legacy) legacyKeys.add(key);
    } else if (ts.isTypeAliasDeclaration(node) || ts.isEnumDeclaration(node)) {
      const key = `${prefix}.${node.name.text}`;
      put(key, 'type', input, node).signatures.push(node.getText(input.file));
      if (legacy) legacyKeys.add(key);
    } else ts.forEachChild(node, child => visit(child, prefix));
  }
  visit(input.file, name);
}

const declarationFiles = {
  mineflayer: ['index.d.ts'], 'mineflayer-pathfinder': ['index.d.ts'],
  'prismarine-block': ['index.d.ts'], 'prismarine-item': ['index.d.ts'], 'prismarine-windows': ['index.d.ts'],
  'prismarine-entity': ['index.d.ts'], vec3: ['index.d.ts'], 'prismarine-recipe': ['index.d.ts'],
  'prismarine-world': ['types/index.d.ts', 'types/world.d.ts', 'types/iterators.d.ts'],
  'prismarine-biome': ['index.d.ts'], 'prismarine-chat': ['index.d.ts'],
  'prismarine-registry': ['lib/index.d.ts'], 'minecraft-data': ['index.d.ts'],
  'prismarine-chunk': ['types/index.d.ts', 'types/section.d.ts'], 'prismarine-nbt': ['typings/index.d.ts'],
  'typed-emitter': ['index.d.ts'],
};
for (const [name, files] of Object.entries(declarationFiles)) for (const file of files) await scanDeclarations(name, file, name === 'mineflayer' || name === 'mineflayer-pathfinder');

// Selected public runtime objects, not every implementation helper/export.
// Source-only discoveries remain candidates until declarations/docs/review
// establish applicability. No inapplicability is inferred from missing types.
async function scanSource(name, path, classNames = {}, targets = {}) {
  const input = await source(name, path, 'source');
  const record = (owner, member, node, binding = 'instance') => {
    const key = `${owner}.${owner === 'events.EventEmitter' && binding === 'static' ? 'static.' : ''}${member}`;
    if (privateMembers.some(e => e.key === key) || (member.startsWith('_') && !declarations.has(key))) {
      sourceFindings.push({ key, classification: 'internal-candidate', status: 'pending-review', ...location(input, node) });
      return;
    }
    const existing = declarations.get(key);
    const entry = put(key, 'member', input, node, { owner, binding });
    if (!existing) { entry.category = 'source-candidate'; entry.applicability = 'pending-review'; }
    if (!declarations.has(owner)) put(owner, 'object', input, node, { category: 'object' });
    const text = node.getText(input.file);
    entry.sourceSignature ??= text.split(/\s*\{\s*\n/)[0].slice(0, 350);
    return entry;
  };
  function resolveTarget(text, owner) {
    if (text === 'this') return owner;
    if (targets[text]) return targets[text];
    if (text.endsWith('.prototype')) return targets[text.slice(0, -10)];
    return classNames[text];
  }
  function walk(node, owner = null) {
    if ((ts.isClassDeclaration(node) || ts.isClassExpression(node)) && node.name && classNames[node.name.text]) {
      owner = classNames[node.name.text];
      for (const clause of node.heritageClauses ?? []) for (const base of clause.types) {
        const expression = base.expression.getText(input.file);
        runtimeInheritance.push({ derived: owner, base: aliases[expression] ?? classNames[expression], expression, source: location(input, base) });
      }
      for (const member of node.members) {
        const key = memberName(member);
        if (key && !ts.isPrivateIdentifier(member.name ?? node.name)) record(owner, key, member, has(member, ts.SyntaxKind.StaticKeyword) ? 'static' : 'instance');
      }
    }
    if (ts.isFunctionDeclaration(node) && node.name && classNames[node.name.text]) {
      owner = classNames[node.name.text];
      record(owner, 'constructor', node);
    }
    if (ts.isBinaryExpression(node) && ts.isPropertyAccessExpression(node.left) && node.operatorToken.kind === ts.SyntaxKind.EqualsToken) {
      const target = node.left.expression.getText(input.file);
      const resolved = resolveTarget(target, owner);
      if (resolved) {
        record(resolved, node.left.name.text, node, target === 'this' || target.endsWith('.prototype') || target.startsWith('bot') ? 'instance' : 'static');
        if (ts.isFunctionExpression(node.right) || ts.isArrowFunction(node.right)) owner = resolved;
      }
    }
    if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression)) {
      const target = node.expression.expression.getText(input.file);
      if (node.expression.name.text === 'emit' && (target === 'bot' || (target === 'this' && owner) || (name === 'events' && target === 'target'))) {
        const event = node.arguments[0];
        if (event && (ts.isStringLiteralLike(event) || ts.isTemplateExpression(event))) {
          const eventName = ts.isTemplateExpression(event) ? event.getText(input.file).slice(1, -1) : event.text;
          const eventOwner = target === 'bot' ? 'mineflayer.BotEvents' : name === 'events' ? 'events.EventEmitter.events' : `${owner}.events`;
          const key = `${eventOwner}.${eventName}`;
          const existing = declarations.has(key);
          put(key, 'event', input, node, { owner: eventOwner, category: 'event', applicability: existing ? 'applicable' : 'pending-review' });
        }
      }
      if (target === 'Object' && ['defineProperty', 'defineProperties'].includes(node.expression.name.text)) {
        const resolved = node.arguments[0] && resolveTarget(node.arguments[0].getText(input.file), owner);
        if (resolved && ts.isStringLiteralLike(node.arguments[1])) record(resolved, node.arguments[1].text, node, 'static');
        if (resolved && ts.isObjectLiteralExpression(node.arguments[1])) for (const p of node.arguments[1].properties) if (memberName(p)) record(resolved, memberName(p), p);
      }
    }
    ts.forEachChild(node, child => walk(child, owner));
  }
  walk(input.file);
}
for (const [name, file, classes, targets] of [
  ['prismarine-block', 'index.js', { Block: 'prismarine-block.Block' }],
  ['prismarine-item', 'index.js', { Item: 'prismarine-item.Item' }],
  ['prismarine-windows', 'lib/Window.js', { Window: 'prismarine-windows.Window' }],
  ['prismarine-entity', 'index.js', { Entity: 'prismarine-entity.Entity' }],
  ['vec3', 'index.js', { Vec3: 'vec3.Vec3' }],
  ['prismarine-recipe', 'lib/recipe.js', { Recipe: 'prismarine-recipe.Recipe' }],
  ['prismarine-recipe', 'lib/recipe_item.js', { RecipeItem: 'prismarine-recipe.RecipeItem' }],
  ['prismarine-chat', 'index.js', { ChatMessage: 'prismarine-chat.ChatMessage' }],
  ['prismarine-biome', 'index.js', { Biome: 'prismarine-biome.Biome' }],
  ['prismarine-world', 'src/worldsync.js', { WorldSync: 'prismarine-world.WorldSync' }],
  ['prismarine-world', 'src/world.js', { World: 'prismarine-world.World' }],
  ['prismarine-world', 'src/iterators.js', Object.fromEntries(['RaycastIterator', 'ManhattanIterator', 'OctahedronIterator', 'SpiralIterator2d'].map(n => [n, `prismarine-world.${n}`]))],
  ['mineflayer-pathfinder', 'lib/movements.js', { Movements: 'mineflayer-pathfinder.Movements' }],
  ['mineflayer-pathfinder', 'lib/move.js', { Move: 'mineflayer-pathfinder.Move' }],
  ['mineflayer-pathfinder', 'lib/goals.js', Object.fromEntries([...declarations.keys()].filter(k => /^mineflayer-pathfinder\.goals\.[^.]+$/.test(k)).map(k => [k.split('.').at(-1), k]))],
  ['mineflayer-pathfinder', 'index.js', {}, { 'bot.pathfinder': 'mineflayer-pathfinder.Pathfinder', bot: 'mineflayer.Bot' }],
  ['events', 'events.js', { EventEmitter: 'events.EventEmitter' }, { EventEmitter: 'events.EventEmitter', 'module.exports': 'events' }],
]) await scanSource(name, file, classes, targets);
for (const file of (await readdir(join(packages.get('mineflayer').root, 'lib/plugins'))).filter(f => f.endsWith('.js')).sort()) {
  await scanSource('mineflayer', `lib/plugins/${file}`, {}, { bot: 'mineflayer.Bot' });
}
// events@3 is the actual guest runtime contract, not a newer @types/node API.
for (const entry of declarations.values()) if (entry.owner === 'events.EventEmitter' && entry.binding === 'instance') {
  entry.category = 'member'; entry.applicability = 'applicable';
}

const documentFiles = {
  mineflayer: ['docs/api.md'], 'mineflayer-pathfinder': ['readme.md'],
  'prismarine-block': ['doc/API.md'], 'prismarine-item': ['README.md'], 'prismarine-windows': ['API.md'],
  'prismarine-entity': ['README.md'], vec3: ['README.md'], 'prismarine-recipe': ['README.md'],
  'prismarine-world': ['docs/API.md'], 'prismarine-biome': ['README.md'], 'prismarine-chat': ['README.md'],
  'prismarine-registry': ['README.md'], 'prismarine-chunk': ['README.md'], 'prismarine-nbt': ['README.md'], events: ['Readme.md'],
};
const docOwners = {
  bot: ['mineflayer.Bot'], block: ['prismarine-block.Block'], item: ['prismarine-item.Item'], window: ['prismarine-windows.Window'],
  windows: ['prismarine-windows.WindowsExports'], entity: ['prismarine-entity.Entity'], vec3: ['vec3.Vec3'],
  recipe: ['prismarine-recipe.Recipe'], recipeitem: ['prismarine-recipe.RecipeItem'],
  world: ['prismarine-world.World', 'prismarine-world.WorldSync'],
  chatmessage: ['prismarine-chat.ChatMessage'], messagebuilder: ['prismarine-chat.MessageBuilder'],
  'bot.pathfinder': ['mineflayer-pathfinder.Pathfinder'], movements: ['mineflayer-pathfinder.Movements'],
};
for (const [name, paths] of Object.entries(documentFiles)) for (const path of paths) {
  const input = await source(name, path, 'documentation');
  for (const match of input.text.matchAll(/^#{2,6}\s+(.+)$/gm)) {
    const heading = match[1], line = input.text.slice(0, match.index).split('\n').length;
    const doc = { id: `${input.id}:${line}`, package: name, heading, input: input.id, line, status: 'pending-review', members: [] };
    const clean = heading.replace(/[`*]/g, '');
    const member = /\b(bot\.pathfinder|\w+)\.([A-Za-z_$][\w$]*)/.exec(clean);
    if (member) for (const owner of docOwners[member[1].toLowerCase()] ?? []) {
      const key = `${owner}.${member[2]}`;
      const entry = declarations.get(key) ?? put(key, 'member', null, null, { owner, category: 'documentation-only', applicability: 'pending-review' });
      (entry.documentation ??= []).push(doc.id); doc.members.push(key);
    }
    const event = /\b(bot|window|world)\s+["']([^"']+)["']/i.exec(clean);
    if (event) for (const owner of docOwners[event[1].toLowerCase()]) {
      const eventOwner = owner === 'mineflayer.Bot' ? 'mineflayer.BotEvents' : `${owner}.events`;
      const key = `${eventOwner}.${event[2]}`;
      const entry = declarations.get(key) ?? put(key, 'event', null, null, { owner: eventOwner, category: 'event', applicability: 'applicable' });
      (entry.documentation ??= []).push(doc.id); doc.members.push(key);
    }
    // Bare documented names (Movements properties, goal constructors and
    // Pathfinder events) are resolved only within this package's inputs.
    if (!doc.members.length) {
      const nameMatch = /^([A-Za-z_$][\w$]*)(?:\(|$)/.exec(clean.trim());
      if (nameMatch) {
        const label = nameMatch[1];
        const candidates = [...declarations.values()].filter(e => e.sources.some(at => at.input.startsWith(`${name}/`)) &&
          (e.key.split('.').at(-1) === label || (e.key.endsWith('.constructor') && e.owner?.split('.').at(-1) === label)));
        for (const entry of candidates) {
          (entry.documentation ??= []).push(doc.id); doc.members.push(entry.key);
        }
      }
    }
    documents.push(doc);
  }
}

// Expand inherited call sites without turning aliases into additional proof.
// Every projection links back to the declaring member and keeps its status.
const forcedBases = { 'prismarine-windows.Window': 'events.EventEmitter', 'typed-emitter.TypedEventEmitter': 'events.EventEmitter' };
for (const [derived, base] of Object.entries(forcedBases)) inheritance.push({ derived, base, reason: 'Pinned runtime EventEmitter; typed-emitter supplies signatures only' });
for (const edge of inheritance) {
  if (edge.base) continue;
  const expr = edge.expression;
  const sibling = `${edge.prefix}.${expr}`;
  const imported = edge.imported[expr];
  edge.base = aliases[expr] ?? (declarations.has(sibling) ? sibling : imported);
  if (expr.includes('EventEmitter')) edge.base = 'events.EventEmitter';
  if (edge.base === 'prismarine-world.world') edge.base = null;
  if (!declarations.has(edge.base)) {
    const matches = [...declarations.keys()].filter(k => k.endsWith(`.${expr}`) && !declarations.get(k).owner);
    if (matches.length === 1) edge.base = matches[0];
  }
  edge.status = declarations.has(edge.base) ? 'resolved' : 'pending-review';
  const runtime = runtimeInheritance.find(e => e.derived === edge.derived);
  if (runtime?.base && runtime.base !== edge.base) {
    edge.runtimeBase = runtime.base;
    edge.status = 'pending-review';
    edge.reason = 'Declaration and source inheritance disagree';
    // The documented deprecated wrapper owns a GoalLookAtBlock; it does not
    // inherit that class's fields or constructor (pinned goals.js:209).
    if (edge.derived === 'mineflayer-pathfinder.goals.GoalBreakBlock'
        && edge.base === 'mineflayer-pathfinder.goals.GoalLookAtBlock'
        && runtime.base === 'mineflayer-pathfinder.goals.Goal') {
      edge.declaredBase = edge.base;
      edge.base = runtime.base;
      edge.status = 'reviewed-source-correction';
      edge.reason = 'Pinned readme.md:421 and goals.js:209 define the Goal wrapper; index.d.ts:199 incorrectly declares GoalLookAtBlock inheritance';
      delete edge.runtimeBase;
    }
  }
  delete edge.imported;
}
const inherited = new Set();
function inherit(owner, visiting = new Set()) {
  if (visiting.has(owner)) throw new Error(`Inheritance cycle: ${owner}`);
  if (inherited.has(owner)) return;
  visiting.add(owner);
  for (const edge of inheritance.filter(e => e.derived === owner && declarations.has(e.base))) {
    inherit(edge.base, visiting);
    for (const entry of [...declarations.values()].filter(e => (e.owner === edge.base || e.owner === `${edge.base}.events`) && e.binding !== 'static' && !e.key.endsWith('.constructor'))) {
      const key = `${owner}.${entry.key.slice(edge.base.length + 1)}`;
      const own = declarations.get(key);
      if (own && own.category !== 'documentation-only') {
        (own.baseMembers ??= []).push(entry.key);
        continue;
      }
      declarations.set(key, { ...entry, key, owner: entry.owner === edge.base ? owner : `${owner}.events`,
        signatures: [...entry.signatures], sources: [...entry.sources],
        documentation: [...new Set([...(entry.documentation ?? []), ...(own?.documentation ?? [])])],
        applicability: edge.runtimeBase ? 'pending-review' : entry.applicability,
        inheritanceReview: edge.runtimeBase ? edge.reason : undefined,
        inheritedFrom: entry.key, canonical: entry.canonical ?? entry.key });
    }
  }
  visiting.delete(owner); inherited.add(owner);
}
for (const edge of inheritance) inherit(edge.derived);
for (const key of legacyKeys) if (!declarations.has(key)) throw new Error(`Legacy key lost: ${key}`);

const evidence = [];
for (const [id, spec] of Object.entries(coverage.evidence ?? {})) {
  if (!['library-differential', 'serialization', 'runtime-boundary', 'native-integration', 'live-reference'].includes(spec.kind)) throw new Error(`Invalid evidence kind: ${id}`);
  for (const subject of spec.subjects ?? []) if (!declarations.has(subject)) throw new Error(`Unknown evidence subject ${subject} in ${id}`);
  let text;
  try { text = await readFile(join(sourceRoot, spec.path)); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (spec.result && !['not-recorded', 'passed', 'failed'].includes(spec.result)) throw new Error(`Invalid evidence result: ${id}`);
  const item = { id, ...spec, recordedSourceSha256: spec.sourceSha256, available: text !== undefined, sourceSha256: text && sha(text), result: spec.result ?? 'not-recorded' };
  if (item.result === 'passed' || item.result === 'failed') {
    if (!spec.report?.path || !spec.report?.sha256 || !spec.sourceSha256) throw new Error(`Recorded evidence needs scenario/report hashes: ${id}`);
    item.reportedResult = item.result;
    let report;
    try { report = await readFile(join(sourceRoot, spec.report.path)); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
    item.verification = !report ? 'report-unavailable'
      : sha(report) !== spec.report.sha256 ? 'report-hash-mismatch'
        : !item.available || spec.sourceSha256 !== item.sourceSha256 ? 'scenario-hash-mismatch' : 'verified';
    if (item.verification === 'verified' && spec.report.codePath) {
      const executed = spec.report.codePath.reduce((value, key) => value?.[key], JSON.parse(report));
      if (typeof executed !== 'string' || executed.trim() !== text.toString().trim()) item.verification = 'executed-code-mismatch';
    }
    if (item.verification !== 'verified') item.result = 'unverified';
  }
  evidence.push(item);
}
// Reviewed connection-only APIs; end/quit close the socket in the pinned source.
const exclusions = new Set(['mineflayer.createBot', 'mineflayer.Bot.connect',
  'mineflayer.Bot.end', 'mineflayer.Bot.quit', 'mineflayer.Bot._client',
  // User confirmed native Mob mechanics without a substitute player/predictor.
  'mineflayer.Bot.player', 'mineflayer.Bot.food', 'mineflayer.Bot.foodSaturation',
  'mineflayer.Bot.respawn', 'mineflayer.Bot.physics', 'mineflayer.Bot.physicsEnabled',
  'mineflayer.Bot.jumpQueued', 'mineflayer.Bot.jumpTicks',
  'mineflayer.Bot.acceptResourcePack', 'mineflayer.Bot.denyResourcePack',
  'mineflayer.BotEvents.resourcePack']);
// Reviewed PC1.21.1 data contracts: no native observation or action is involved.
// Keep this explicit so an arbitrary gameplay member cannot opt out of native
// evidence by setting a flag in coverage.json. Dynamic registries are separate.
const pinnedDataContracts = new Set([
  'attributes', 'attributesArray', 'attributesByName', 'blockCollisionShapes', 'blockLoot',
  'blockMappings', 'blocks', 'blocksArray', 'blocksByName', 'blocksByStateId',
  'blockStates', 'commands', 'defaultSkin', 'effects', 'effectsArray',
  'effectsByName', 'enchantments', 'enchantmentsArray', 'enchantmentsByName', 'entities',
  'entitiesArray', 'entitiesByName', 'entityLoot', 'foods', 'foodsArray',
  'foodsByName', 'instruments', 'instrumentsArray', 'isNewerOrEqualTo', 'isOlderThan',
  'items', 'itemsArray', 'itemsByName', 'language', 'loginPacket',
  'mapIcons', 'mapIconsArray', 'mapIconsByName', 'materials', 'mobs',
  'objects', 'particles', 'particlesArray', 'particlesByName', 'protocol',
  'protocolComments', 'protocolYaml', 'recipes', 'tints', 'type',
  'version', 'windows', 'windowsArray', 'windowsByName',
].map(name => `minecraft-data.MinecraftData.IndexedData.${name}`));
// Nested fixed table values are included in the complete registry serialization comparison.
const pinnedDataShapeContracts = new Set([
  "minecraft-data.MinecraftData.Block.boundingBox",
  "minecraft-data.MinecraftData.Block.defaultState",
  "minecraft-data.MinecraftData.Block.diggable",
  "minecraft-data.MinecraftData.Block.displayName",
  "minecraft-data.MinecraftData.Block.drops",
  "minecraft-data.MinecraftData.Block.emitLight",
  "minecraft-data.MinecraftData.Block.filterLight",
  "minecraft-data.MinecraftData.Block.hardness",
  "minecraft-data.MinecraftData.Block.harvestTools",
  "minecraft-data.MinecraftData.Block.id",
  "minecraft-data.MinecraftData.Block.material",
  "minecraft-data.MinecraftData.Block.maxStateId",
  "minecraft-data.MinecraftData.Block.minStateId",
  "minecraft-data.MinecraftData.Block.name",
  "minecraft-data.MinecraftData.Block.resistance",
  "minecraft-data.MinecraftData.Block.stackSize",
  "minecraft-data.MinecraftData.Block.states",
  "minecraft-data.MinecraftData.Block.transparent",
  "minecraft-data.MinecraftData.Item.displayName",
  "minecraft-data.MinecraftData.Item.enchantCategories",
  "minecraft-data.MinecraftData.Item.id",
  "minecraft-data.MinecraftData.Item.maxDurability",
  "minecraft-data.MinecraftData.Item.name",
  "minecraft-data.MinecraftData.Item.repairWith",
  "minecraft-data.MinecraftData.Item.stackSize",
  "minecraft-data.MinecraftData.Entity.category",
  "minecraft-data.MinecraftData.Entity.displayName",
  "minecraft-data.MinecraftData.Entity.height",
  "minecraft-data.MinecraftData.Entity.id",
  "minecraft-data.MinecraftData.Entity.internalId",
  "minecraft-data.MinecraftData.Entity.metadataKeys",
  "minecraft-data.MinecraftData.Entity.name",
  "minecraft-data.MinecraftData.Entity.type",
  "minecraft-data.MinecraftData.Entity.width",
  "minecraft-data.MinecraftData.Particle.id",
  "minecraft-data.MinecraftData.Particle.name",
  "minecraft-data.MinecraftData.Window.id",
  "minecraft-data.MinecraftData.Window.name",
  "minecraft-data.MinecraftData.Window.openedWith",
  "minecraft-data.MinecraftData.Window.properties",
  "minecraft-data.MinecraftData.Window.slots",
  "minecraft-data.MinecraftData.BlockCollisionShapes.blocks",
  "minecraft-data.MinecraftData.BlockCollisionShapes.shapes",
  "minecraft-data.MinecraftData.Tints.constant",
  "minecraft-data.MinecraftData.Tints.foliage",
  "minecraft-data.MinecraftData.Tints.grass",
  "minecraft-data.MinecraftData.Tints.redstone",
  "minecraft-data.MinecraftData.Tints.water",
  "minecraft-data.MinecraftData.Version.['<']",
  "minecraft-data.MinecraftData.Version.['<=']",
  "minecraft-data.MinecraftData.Version.['==']",
  "minecraft-data.MinecraftData.Version.['>']",
  "minecraft-data.MinecraftData.Version.['>=']",
  "minecraft-data.MinecraftData.Version.dataVersion",
  "minecraft-data.MinecraftData.Version.majorVersion",
  "minecraft-data.MinecraftData.Version.minecraftVersion",
  "minecraft-data.MinecraftData.Version.releaseType",
  "minecraft-data.MinecraftData.Version.type",
  "minecraft-data.MinecraftData.Version.version",
  "minecraft-data.MinecraftData.Attribute.default",
  "minecraft-data.MinecraftData.Attribute.max",
  "minecraft-data.MinecraftData.Attribute.min",
  "minecraft-data.MinecraftData.Attribute.name",
  "minecraft-data.MinecraftData.Attribute.resource",
  "minecraft-data.MinecraftData.Effect.displayName",
  "minecraft-data.MinecraftData.Effect.id",
  "minecraft-data.MinecraftData.Effect.name",
  "minecraft-data.MinecraftData.Effect.type",
  "minecraft-data.MinecraftData.Enchantment.category",
  "minecraft-data.MinecraftData.Enchantment.curse",
  "minecraft-data.MinecraftData.Enchantment.discoverable",
  "minecraft-data.MinecraftData.Enchantment.displayName",
  "minecraft-data.MinecraftData.Enchantment.exclude",
  "minecraft-data.MinecraftData.Enchantment.id",
  "minecraft-data.MinecraftData.Enchantment.maxCost",
  "minecraft-data.MinecraftData.Enchantment.maxLevel",
  "minecraft-data.MinecraftData.Enchantment.minCost",
  "minecraft-data.MinecraftData.Enchantment.name",
  "minecraft-data.MinecraftData.Enchantment.tradeable",
  "minecraft-data.MinecraftData.Enchantment.treasureOnly",
  "minecraft-data.MinecraftData.Enchantment.weight",
  "minecraft-data.MinecraftData.Food.displayName",
  "minecraft-data.MinecraftData.Food.effectiveQuality",
  "minecraft-data.MinecraftData.Food.foodPoints",
  "minecraft-data.MinecraftData.Food.id",
  "minecraft-data.MinecraftData.Food.name",
  "minecraft-data.MinecraftData.Food.saturation",
  "minecraft-data.MinecraftData.Food.saturationRatio",
  "minecraft-data.MinecraftData.Food.stackSize",
  "minecraft-data.MinecraftData.Food.variations",
  "minecraft-data.MinecraftData.Instrument.id",
  "minecraft-data.MinecraftData.Instrument.name",
  "minecraft-data.MinecraftData.Instrument.sound",
]);
// Vec3 members transform caller-supplied values only. Native position hydration
// and ownership are separate Entity/Bot obligations, not vector arithmetic.
const pureVectorContracts = new Set([
  'constructor', 'x', 'y', 'z', 'abs', 'add', 'at', 'clone', 'cross',
  'distanceSquared', 'distanceTo', 'divide', 'dot', 'equals', 'floor', 'floored',
  'innerProduct', 'isZero', 'manhattanDistanceTo', 'max', 'min', 'minus', 'modulus',
  'multiply', 'norm', 'normalize', 'offset', 'plus', 'round', 'rounded', 'scale',
  'scaled', 'set', 'subtract', 'toArray', 'toString', 'translate', 'unit', 'update',
  'volume', 'xy', 'xyDistanceTo', 'xz', 'xzDistanceTo', 'xzy', 'yz', 'yzDistanceTo',
].map(name => `vec3.Vec3.${name}`));
// These methods render/transform supplied component data. Native delivery and
// registry chat-type initialization are not covered by this classification.
const pureChatContracts = new Set([
  'constructor', 'fromNotch', 'fromNetwork', 'json', 'extra', 'translate',
  'selector', 'keybind', 'score',
  // Existing chat differential records complete parsed fields before/after rendering.
  'bold', 'clickEvent', 'color', 'fallback', 'hoverEvent', 'italic', 'obfuscated',
  'reset', 'strikethrough', 'text', 'underlined', 'with',
  'append', 'clone', 'getText', 'length', 'parse', 'toAnsi', 'toHTML', 'toMotd',
  'toString', 'valueOf',
].map(name => `prismarine-chat.ChatMessage.${name}`));
const pureBuilderContracts = new Set([
  "fromNetwork",
  "addExtra",
  "addWith",
  "fromString",
  "resetFormatting",
  "setBold",
  "setClickEvent",
  "setColor",
  "setFont",
  "setHoverEvent",
  "setInsertion",
  "setItalic",
  "setKeybind",
  "setObfuscated",
  "setScore",
  "setSelector",
  "setStrikethrough",
  "setText",
  "setTranslate",
  "setUnderlined",
  "toJSON",
  "toString",
  "bold",
  "clickEvent",
  "color",
  "extra",
  "font",
  "hoverEvent",
  "insertion",
  "italic",
  "keybind",
  "obfuscated",
  "score",
  "selector",
  "strikethrough",
  "text",
  "translate",
  "underlined",
  "with"
].map(name => `prismarine-chat.MessageBuilder.${name}`));
// Recipe objects use supplied static recipe data; they do not craft or hydrate inventory.
const pureRecipeContracts = new Set([
  "Recipe.constructor",
  "Recipe.delta",
  "Recipe.find",
  "Recipe.ingredients",
  "Recipe.inShape",
  "Recipe.outShape",
  "Recipe.requiresTable",
  "Recipe.result",
  "RecipeItem.clone",
  "RecipeItem.constructor",
  "RecipeItem.count",
  "RecipeItem.fromEnum",
  "RecipeItem.id",
  "RecipeItem.metadata"
].map(name => `prismarine-recipe.${name}`));
// Calculations/local NBT transforms over supplied block data, not live world reads.
const pureBlockContracts = new Set([
  "constructor",
  "fromStateId",
  "fromProperties",
  "fromString",
  "getProperties",
  "canHarvest",
  "digTime",
  "getSignText",
  "setSignText",
  "type",
  "metadata",
  "stateId",
  "name",
  "hardness",
  "displayName",
  "shapes",
  "boundingBox",
  "transparent",
  "diggable",
  "material",
  "harvestTools",
  "drops",
  "isWaterlogged",
  "getHash"
].map(name => `prismarine-block.Block.${name}`));
// Supplied Item components/NBT and local calculations only; no inventory mutation.
const pureItemContracts = new Set([
  "constructor",
  "equal",
  "toNotch",
  "fromNotch",
  "currentStackId",
  "nextStackId",
  "anvil",
  "customName",
  "customLore",
  "repairCost",
  "customModel",
  "enchants",
  "blocksCanPlaceOn",
  "blocksCanDestroy",
  "durabilityUsed",
  "spawnEggMobName"
].map(name => `prismarine-item.Item.${name}`));
// Local Entity construction/accessors only; native observation fields are separate.
const pureEntityContracts = new Set([
  'constructor', 'setEquipment', 'getCustomName', 'getDroppedItem',
  'heldItem', 'mobType', 'objectType',
].map(name => `prismarine-entity.Entity.${name}`));
// Registration/dispatch on the guest emitter; not a claim about native event sources.
const pureEmitterContracts = new Set([
  // events3 assigns these two aliases to the same tested on/off functions.
  'addListener', 'removeListener',
  'on', 'once', 'prependListener', 'listenerCount', 'eventNames', 'listeners',
  'rawListeners', 'emit', 'off', 'removeAllListeners', 'setMaxListeners',
  'prependOnceListener', 'getMaxListeners',
].map(name => `events.EventEmitter.${name}`));
// Window's synchronous local model is separate from native inventory operations.
const pureWindowContracts = new Set([
  "constructor",
  "findItemRange",
  "findItemsRange",
  "findItemRangeName",
  "findInventoryItem",
  "findContainerItem",
  "firstEmptySlotRange",
  "lastEmptySlotRange",
  "firstEmptyHotbarSlot",
  "firstEmptyContainerSlot",
  "firstEmptyInventorySlot",
  "sumRange",
  "countRange",
  "itemsRange",
  "count",
  "containerCount",
  "items",
  "containerItems",
  "emptySlotCount",
  "transactionRequiresConfirmation",
  "updateSlot",
  "acceptClick",
  "acceptOutsideWindowClick",
  "acceptInventoryClick",
  "acceptNonInventorySwapAreaClick",
  "acceptSwapAreaLeftClick",
  "acceptSwapAreaRightClick",
  "acceptCraftingClick",
  "fillAndDump",
  "fillSlotsWithItem",
  "fillSlotWithItem",
  "splitSlot",
  "fillSlotWithSelectedItem",
  "swapSelectedItem",
  "clear",
  "mouseClick",
  "shiftClick",
  "numberClick",
  "middleClick",
  "dropClick",
  "dropSelectedItem",
  "dumpItem",
  "dragClick",
  "doubleClick"
].map(name => `prismarine-windows.Window.${name}`));
// Local predicates imported unchanged from the pinned goals module. These do not
// establish Pathfinder consumption, native arrivals or entity/world hydration.
const pureGoalContracts = new Set(Object.entries({
  Goal: ['heuristic', 'isEnd', 'hasChanged', 'isValid'],
  GoalBlock: ['constructor', 'x', 'y', 'z', 'heuristic', 'isEnd', 'hasChanged', 'isValid'],
  GoalNear: ['constructor', 'x', 'y', 'z', 'rangeSq', 'heuristic', 'isEnd', 'hasChanged', 'isValid'],
  GoalXZ: ['constructor', 'x', 'z', 'heuristic', 'isEnd', 'hasChanged', 'isValid'],
  GoalNearXZ: ['constructor', 'x', 'z', 'rangeSq', 'heuristic', 'isEnd', 'hasChanged', 'isValid'],
  GoalY: ['constructor', 'y', 'heuristic', 'isEnd', 'hasChanged', 'isValid'],
  GoalGetToBlock: ['constructor', 'x', 'y', 'z', 'heuristic', 'isEnd', 'hasChanged', 'isValid'],
  GoalCompositeAny: ['constructor', 'goals', 'push', 'heuristic', 'isEnd', 'hasChanged', 'isValid'],
  GoalCompositeAll: ['constructor', 'goals', 'push', 'heuristic', 'isEnd', 'hasChanged', 'isValid'],
  GoalInvert: ['constructor', 'goal', 'heuristic', 'isEnd', 'hasChanged', 'isValid'],
}).flatMap(([name, members]) => members.map(member => `mineflayer-pathfinder.goals.${name}.${member}`)));
// These are pinned PC version-query return contracts, not native gameplay support.
const pureFeatureContracts = new Set([
  'mineflayer.Bot.supportFeature', 'minecraft-data.MinecraftData.IndexedData.supportFeature',
  ...[
    "acknowledgePlayerDigging", "actionIdUsed", "allEntityEquipmentInOne", "anvilNameLengthIsFifty",
    "armAnimationBeforeUse", "attachStackEntity", "attackUsesOwnPacket", "attributeSnakeCase",
    "biomesSentSeparately", "blockMetadata", "blockPlaceHasHandAndFloatCursor", "blockPlaceHasHandAndIntCursor",
    "blockPlaceHasHeldItem", "blockPlaceHasInsideBlock", "blockPlaceHasIntCursor", "blockSchemeIsFlat",
    "blockStateId", "booksUseStoredEnchantments", "chainedChatWithHashing", "chatCommandsQueuedToMainThread",
    "chatGlobalIndexAndChecksum", "chatPacketsUseNbtComponents", "chatTypeIsHolder", "clientUpdateBookIdWhenSign",
    "clientboundChatHasSender", "clientsideChatFormatting", "consolidatedEntitySpawnPacket", "creativeSleepNearMobs",
    "customChannelIdentifier", "customChannelMCPrefixed", "difficultySentSeparately", "dimensionDataInCodec",
    "dimensionDataIsAvailable", "dimensionIsAString", "dimensionIsAWorld", "dimensionIsAnInt",
    "doesntHaveChestType", "doesntHaveOffHandSlot", "doublePosition", "editBookIsPluginChannel",
    "editBookPacketUsesNbt", "effectAreMinecraftPrefixed", "effectAreNotPrefixed", "effectNamesMatchRegistryName",
    "enchantmentsComponentIsFlat", "enderCrystalNameEndsInErNoCaps", "enderCrystalNameNoCapsWithUnderscore", "entityCamelCase",
    "entityMCPrefixed", "entityMetadataHasLong", "entityMetadataSentSeparately", "entityNameLowerCaseNoUnderscore",
    "entityNameUpperCaseNoUnderscore", "entitySnakeCase", "entityTeleportHasRelativeFlags", "entityVelocityIsLpVec3",
    "explicitMaxDurability", "fireworkMetadataOptVarInt8", "fireworkMetadataOptVarInt9", "fireworkMetadataVarInt7",
    "fireworkNamePlural", "fireworkNameSingular", "fishingBiteDelayMaxTicks", "fishingBobberCorrectlyNamed",
    "fixedPointDelta", "fixedPointDelta128", "fixedPointPosition", "furnaceNbtUsesSnakeCase",
    "gameRuleUsesResourceLocation", "hasAttackCooldown", "hasBundlePacket", "hasConfigurationState",
    "hasDataCommand", "hasEditBookPacket", "hasElytraFlying", "hasExecuteCommand",
    "hasItemCommand", "indexesVillagerRecipes", "itemLoreIsAString", "itemSerializationAllowsPresent",
    "itemSerializationUsesBlockId", "itemSerializationWillOnlyUsePresent", "itemsAreAlsoBlocks", "itemsAreNotBlocks",
    "itemsWithComponents", "lessCharsInChat", "lightSentSeparately", "mcDataHasEntityMetadata",
    "metadataIxOfItem", "mobSpawner", "multiBlockChangeHasTrustEdges", "multiSidedSigns",
    "multiTypeSigns", "nbtNameForEnchant", "nbtOnMetadata", "netherUpdateInventoryWindows",
    "newLightingDataFormat", "newPlayerInputPacket", "noAckOnCreateSetSlotPacket", "noteBlockNameIsNoteBlock",
    "oneBlockForSeveralVariations", "playerInfoActionIsBitfield", "playsoundUsesResourceLocation", "positionPacketHasBitflags",
    "positionUpdateSentEveryTick", "profileKeySignatureV2", "quickMoveClickSendsEmptyItem", "registryDataIsMandatory",
    "removedNamedSoundEffectPacket", "replaceItemSlotIsPrefixed", "resourcePackUsesHash", "resourcePackUsesUUID",
    "respawnIsActionId", "respawnIsPayload", "saveDurabilityAsDamage", "segmentedRegistryCodecData",
    "selectingTradeMovesItems", "sendStringifiedSignText", "sendsClientTickEndPacket", "sendsPlayerLoadedPacket",
    "seperateSignedChatCommandPacket", "setBlockUsesMetadataNumber", "setPassengerStackEntity", "setSlotAsTransaction",
    "shieldSlot", "signatureEncryption", "signedChat", "sneakUsesEntityAction",
    "spawnEggsHaveSpawnedEntityInName", "spawnEggsUseEntityTagInNbt", "spawnEggsUseInternalIdInNbt", "spawnPositionIsGlobal",
    "spawnRespawnWorldDataField", "spawner", "stateIdUsed", "tabCompleteHasAToolTip",
    "tabCompleteHasNoToolTip", "tallWorld", "teamUsesChatComponents", "teamUsesScoreboard",
    "teleportUsesOwnPacket", "teleportUsesPositionPacket", "theFlattening", "theShulkerBoxes",
    "titleUsesLegacyPackets", "titleUsesNewPackets", "transactionPacketExists", "typeOfValueForEnchantLevel",
    "unifiedPlayerAndEntitySpawnPacket", "unloadChunkByEmptyChunk", "unloadChunkDirect", "updateViewPosition",
    "updatedParticlesPacket", "useChatSessions", "useEntityHasLocation", "useItemWithBlockPlace",
    "useItemWithOwnPacket", "useMCItemName", "useMCTrList", "useMCTrSel",
    "usesAdvCdm", "usesAdvCmd", "usesBlockStates", "usesLoginPacket",
    "usesMultiblock3DChunkCoords", "usesMultiblockSingleLong", "usesOldSoundPacket", "usesPalettedChunks",
    "usetraderlist", "village&pillageInventoryWindows", "whereDurabilityIsSerialized",
  ].map(name => `minecraft-data.MinecraftData.SupportsFeature.${name}`)
]);
const purePluginContracts = ['mineflayer.Bot.loadPlugin', 'mineflayer.Bot.loadPlugins', 'mineflayer.Bot.hasPlugin'];
const pureLibraryContracts = new Set([...purePluginContracts, ...pinnedDataContracts, ...pinnedDataShapeContracts, ...pureVectorContracts, ...pureChatContracts, ...pureBuilderContracts, ...pureRecipeContracts, ...pureBlockContracts, ...pureItemContracts, ...pureEntityContracts, ...pureEmitterContracts, ...pureWindowContracts, ...pureGoalContracts, ...pureFeatureContracts]);
for (const [key, decision] of Object.entries(coverage.entries)) {
  const entry = declarations.get(key);
  if (!entry) throw new Error(`Coverage key absent from inventory: ${key}`);
  if (!['supported', 'pending', 'inapplicable'].includes(decision.status)) throw new Error(`Invalid coverage status: ${key}`);
  // This Java Edition pin cannot instantiate the dependency's Bedrock-only class.
  // Keep PCChunk and shared APIs as independent applicable obligations.
  const otherEdition = entry.owner === 'prismarine-chunk.BedrockChunk';
  if (decision.status === 'inapplicable' && ((!exclusions.has(key) && !otherEdition) || !decision.reason)) throw new Error(`Unreviewed scope exclusion: ${key}`);
  if (decision.status === 'supported') {
    if (!decision.scenarios?.length || !decision.evidence?.length) throw new Error(`Supported API needs scenarios/evidence: ${key}`);
    if ((decision.applicability ?? entry.applicability) !== 'applicable') throw new Error(`Applicability needs review: ${key}`);
    const reports = decision.evidence.map(id => evidence.find(e => e.id === id));
    if (reports.some(e => !e || e.result !== 'passed')) throw new Error(`Unverified supporting evidence: ${key}`);
    if (decision.scenarios.some(path => !reports.some(e => e.path === path))) throw new Error(`Scenario lacks a verified run: ${key}`);
    if (reports.some(e => !(e.subjects ?? []).some(s => key === s || key.startsWith(`${s}.`) || entry.canonical === s || entry.canonical?.startsWith(`${s}.`)))) throw new Error(`Evidence is unrelated to API: ${key}`);
    const verification = decision.verification ?? 'native-gameplay';
    if (!['pinned-library', 'native-gameplay'].includes(verification)) throw new Error(`Unknown verification contract: ${key}`);
    if (verification === 'pinned-library' && (!pureLibraryContracts.has(key) || !decision.reason))
      throw new Error(`${key} has no reviewed pure-library contract`);
    const required = verification === 'pinned-library' ? ['library-differential'] : ['native-integration', 'live-reference'];
    for (const kind of required) if (!reports.some(e => e.kind === kind)) throw new Error(`${key} lacks ${kind} conformance evidence`);
  }
  Object.assign(entry, decision);
}
for (const [key, note] of Object.entries(coverage.reviewNotes ?? {})) {
  if (!declarations.has(key)) throw new Error(`Unknown review subject: ${key}`);
  declarations.get(key).reviewNote = note;
}
for (const entry of declarations.values()) {
  entry.relatedEvidence = evidence.filter(e => (e.subjects ?? []).some(s => entry.key === s || entry.owner === s || entry.canonical?.startsWith(`${s}.`) || entry.baseMembers?.some(b => b === s || b.startsWith(`${s}.`)))).map(e => e.id);
}
// Locate guest registrations separately from conformance. A source assignment
// cannot prove correct native behavior; missing sites may be indirect installs.
const implementationInputs = [], implementationSites = [];
const guestTargets = { bot: 'mineflayer.Bot', 'bot.creative': 'mineflayer.creativeMethods',
  pathfinder: 'mineflayer-pathfinder.Pathfinder', 'bot.pathfinder': 'mineflayer-pathfinder.Pathfinder' };
for (const name of (await readdir(join(sourceRoot, 'bb-plugin/scripting'))).filter(name => name.endsWith('.mjs') && name !== 'build.mjs').sort()) {
  const path = `bb-plugin/scripting/${name}`;
  const text = await readFile(join(sourceRoot, path), 'utf8');
  const file = ts.createSourceFile(path, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.JS);
  implementationInputs.push({ path, sha256: sha(text) });
  function record(owner, member, node) {
    if (!owner || !member) return;
    const key = `${owner}.${member}`;
    const site = { key, path, line: file.getLineAndCharacterOfPosition(node.getStart(file)).line + 1 };
    implementationSites.push(site);
    const entry = declarations.get(key);
    if (entry) (entry.implementationSources ??= []).push(site);
  }
  function object(owner, value) {
    if (value && ts.isObjectLiteralExpression(value)) for (const member of value.properties)
      record(owner, memberName(member), member);
  }
  function visit(node) {
    if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.EqualsToken && ts.isPropertyAccessExpression(node.left))
      record(guestTargets[node.left.expression.getText(file)], node.left.name.text, node);
    if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression) && node.expression.expression.getText(file) === 'Object') {
      const target = node.arguments[0] && guestTargets[node.arguments[0].getText(file)];
      if (node.expression.name.text === 'defineProperty' && node.arguments[1] && ts.isStringLiteralLike(node.arguments[1]))
        record(target, node.arguments[1].text, node);
      if (node.expression.name.text === 'defineProperties') object(target, node.arguments[1]);
      if (node.expression.name.text === 'assign') {
        const owner = target ?? (ts.isVariableDeclaration(node.parent) ? guestTargets[node.parent.name.getText(file)] : undefined);
        for (const value of node.arguments.slice(1)) object(owner, value);
      }
    }
    ts.forEachChild(node, visit);
  }
  visit(file);
}
// Discover new lead procedures/reports without claiming they ran or passed.
// Only index names/hashes; do not copy potentially sensitive report payloads.
async function listIfPresent(path) {
  try { return (await readdir(path)).sort(); }
  catch (error) { if (error.code === 'ENOENT') return []; throw error; }
}
const artifactReview = [];
for (const file of (await listIfPresent(join(sourceRoot, 'run/mineflayer-reference'))).filter(f => f.endsWith('-native.json'))) {
  const path = `run/mineflayer-reference/${file}`;
  const bytes = await readFile(join(sourceRoot, path));
  artifactReview.push({ path, sha256: sha(bytes), evidence: evidence.filter(e => e.report?.path === path).map(e => e.id), status: 'pending-review' });
}
const scenarioReview = [];
for (const file of (await listIfPresent(join(sourceRoot, 'tools/mineflayer-reference'))).filter(f => /^(observe-|navigate-).*\.js$/.test(f))) {
  const path = `tools/mineflayer-reference/${file}`;
  if (!evidence.some(e => e.path === path)) scenarioReview.push({ path, sha256: sha(await readFile(join(sourceRoot, path))), status: 'pending-review' });
}
const output = join(root, 'run/mineflayer-reference');
await mkdir(output, { recursive: true });
const entries = [...declarations.values()].sort((a, b) => a.key.localeCompare(b.key));
const count = list => Object.fromEntries(['supported', 'pending', 'inapplicable'].map(s => [s, list.filter(e => e.status === s).length]));
const obligations = entries.filter(e => !e.inheritedFrom && ['member', 'event', 'factory', 'documentation-only'].includes(e.category));
const report = {
  schemaVersion: 2, minecraft: pins.minecraft, inputs: [...inputs.values()],
  packages: [...packages.values()].map(({ root, ...p }) => p),
  counts: count(obligations), countUnit: 'Canonical member/event/factory records only; no completion percentage',
  declarations: entries, inheritance, runtimeInheritance, documentationReview: documents,
  sourceReview: [...sourceFindings, ...entries.filter(e => e.category === 'source-candidate' && !e.inheritedFrom).map(e => ({ key: e.key, status: 'pending-review', sources: e.sources }))],
  privateDeclarations: privateMembers,
  implementationInputs, implementationSites,
  implementationNote: 'Source locations only, not supported status or implementation completeness. Indirect installs, inherited implementations and computed registrations may have no site. Native source and executable evidence require review.',
  dependencyReview: imports.filter(i => !declarationFiles[i.module] && !['events', 'typed-emitter'].includes(i.module)).map(i => ({ ...i, status: 'pending-review' })),
  evidence, artifactReview, scenarioReview, evidencePolicy: coverage.evidencePolicy, liveReferenceRunsRecorded: evidence.filter(e => e.kind === 'live-reference' && e.result !== 'not-recorded').length, legacyKeysPreserved: legacyKeys.size,
  note: 'Discovery is not conformance. Related suites do not prove every member. Data shapes and source candidates are separately reviewable; pending requirements are not exclusions. Live-reference results are recorded separately, never inferred from library checks.',
};
await writeFile(join(output, 'catalog.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ catalog: join(output, 'catalog.json'), canonicalRecords: report.counts, sourceCandidates: report.sourceReview.length, unresolvedInheritance: inheritance.filter(e => e.status === 'pending-review').map(e => ({ derived: e.derived, expression: e.expression })), missingEvidenceFiles: evidence.filter(e => !e.available).map(e => e.path), unverifiedReports: evidence.filter(e => e.result === 'unverified').map(e => ({ id: e.id, reason: e.verification })), legacyKeysPreserved: legacyKeys.size, note: report.note }, null, 2));
