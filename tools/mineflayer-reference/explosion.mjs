// Authored before the adapter. Runs pinned source first; no client or server.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../..', import.meta.url));
const reference = createRequire(new URL('./package.json', import.meta.url));
const plugin = createRequire(new URL('../../bb-plugin/package.json', import.meta.url));
assert.equal(reference('mineflayer/package.json').version, '4.39.0');
const injectReference = reference('mineflayer/lib/plugins/explosion');
const WorldSync = reference('prismarine-world/src/worldsync');
const { Vec3 } = reference('vec3');
function exercise(install, makeWorld, Vec3, s) {
  const lookup = p => s.unknown && p.x === 1 ? null : {
    position: p.floored(), shapes: s.wall && p.x === 1 ? [[0, 0, 0, 1, s.wall === 'slab' ? 0.5 : 1, 1]] : []
  };
  const bot = { world: makeWorld(lookup), blockAt: lookup, game: { difficulty: s.difficulty ?? 'normal' } };
  install(bot);
  const attr = (value, modifiers = []) => ({ value, modifiers });
  const attributes = s.noArmor ? {} : { 'generic.armor': attr(s.armor ?? 0, s.modifiers),
    [s.modern ? 'generic.armor_toughness' : 'generic.armorToughness']: attr(s.toughness ?? 0) };
  const entity = { position: new Vec3(0.5, 0, 0.5), width: s.width ?? 0.6, height: s.height ?? 1.8,
    type: s.type ?? 'player', attributes };
  try { return bot.getExplosionDamages(entity, new Vec3(...(s.source ?? [3.5, 1, 0.5])), s.power ?? 4, !!s.raw); }
  catch (e) { return { error: e.name }; }
}
const cases = [];
for (const difficulty of ['peaceful', 'easy', 'normal', 'hard'])
  for (const armor of [0, 20]) cases.push({ difficulty, armor });
for (const wall of [false, true, 'slab']) for (const raw of [false, true]) cases.push({ wall, raw, armor: 12, toughness: 8 });
cases.push({ noArmor: true }, { noArmor: true, raw: true }, { source: [20, 0, 0], noArmor: true },
  { type: 'mob', armor: 8, modifiers: [{ operation: 0, amount: 3 }, { operation: 1, amount: .5 }, { operation: 2, amount: .2 }] });
const referenceWorld = lookup => Object.assign(new WorldSync(null), { getBlock: lookup });
const expected = cases.map(s => exercise(injectReference, referenceWorld, Vec3, s));
assert(expected.some(value => typeof value === 'number' && value > 0));
assert.equal(exercise(injectReference, referenceWorld, Vec3, { modern: true }).error, 'TypeError');
if (process.argv.includes('--reference-only')) {
  console.log(`Reference ready: ${cases.length} ordinary cases; modern toughness key defect reproduced.`);
} else {
  const { build } = plugin('esbuild');
  const { getQuickJS } = plugin('quickjs-emscripten');
  const bundle = await build({ stdin: { resolveDir: root, contents: `
    import { installExplosion } from './bb-plugin/scripting/explosion.mjs';
    import { createWorldView } from './bb-plugin/scripting/world-view.mjs';
    import { Vec3 } from 'vec3';
    globalThis.install=installExplosion;globalThis.makeWorld=createWorldView;globalThis.Vec3=Vec3;
  ` }, bundle: true, write: false, platform: 'browser', format: 'iife', target: 'es2022', alias: { vec3: plugin.resolve('vec3') } });
  const vm = (await getQuickJS()).newContext();
  vm.runtime.setMemoryLimit(64 * 1024 * 1024); vm.runtime.setMaxStackSize(512 * 1024);
  let deadline;
  vm.runtime.setInterruptHandler(() => Date.now() > deadline);
  function evaluate(code) {
    deadline = Date.now() + 5000;
    const result = vm.evalCode(code), handle = result.error ?? result.value;
    const value = vm.dump(handle); handle.dispose();
    if (result.error) throw new Error(JSON.stringify(value));
    return value;
  }
  const guest = s => evaluate(`exercise(install,makeWorld,Vec3,${JSON.stringify(s)})`);
  try {
    evaluate(bundle.outputFiles[0].text); evaluate(`globalThis.exercise=${exercise};`);
    for (let i = 0; i < cases.length; i++) assert.deepEqual(guest(cases[i]), expected[i], JSON.stringify(cases[i]));
    assert.equal(guest({ modern: true }), expected[4], 'modern toughness equals the equivalent known legacy attributes');
    assert.equal(guest({ unknown: true }), null, 'unobserved ray cells cannot establish exposure');
    assert.equal(guest({ width: 0, raw: true }).error, 'RangeError');
    assert.equal(guest({ width: 100, height: 100, raw: true }).error, 'RangeError');
    assert.equal(guest({ power: -1 }).error, 'RangeError');
    assert.notEqual(guest({ wall: 'slab', height: 0.3, raw: true }), guest({ wall: 'slab', height: 1.8, raw: true }), 'actual body height changes exposure');
    console.log(`PASS: ${cases.length} pinned comparisons and six correction/bound checks in QuickJS; estimate only, no native damage claim.`);
  } finally { vm.dispose(); }
}
