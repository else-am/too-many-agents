// Portability contract: pinned host Vec3 against the actual browser/QuickJS class.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';
const reference = createRequire(new URL('./package.json', import.meta.url));
const plugin = createRequire(new URL('../../bb-plugin/package.json', import.meta.url));
assert.equal(JSON.parse(await readFile(join(dirname(reference.resolve('vec3')), 'package.json'))).version, '0.1.10');
assert.equal(JSON.parse(await readFile(join(dirname(plugin.resolve('vec3')), 'package.json'))).version, '0.1.10');
const { Vec3 } = reference('vec3');
const unary = ['abs', 'clone', 'floor', 'floored', 'isZero', 'norm', 'normalize',
  'round', 'rounded', 'toArray', 'toString', 'unit', 'volume', 'xy', 'xz', 'xzy', 'yz'];
const binary = ['add', 'cross', 'distanceSquared', 'distanceTo', 'divide', 'dot',
  'equals', 'innerProduct', 'manhattanDistanceTo', 'max', 'min', 'minus', 'modulus',
  'multiply', 'plus', 'subtract', 'update', 'xyDistanceTo', 'xzDistanceTo', 'yzDistanceTo'];
const scalar = ['scale', 'scaled'];
const triple = ['set', 'offset', 'translate'];
const inputs = [[0, 0, 0], [-1.5, 2.75, -0], [3, 4, 12], [1e100, -1e-100, 0]];
const cases = [];
for (const xyz of inputs) {
  cases.push({ member: 'constructor', xyz, args: [] });
  for (const member of unary) cases.push({ member, xyz, args: [] });
  for (const member of binary) for (const other of [[0, 0, 0], [-2, 3, 0.5]])
    cases.push({ member, xyz, other, args: member === 'equals' ? [0.01] : [] });
  for (const member of scalar) for (const n of [0, -2, 0.5]) cases.push({ member, xyz, args: [n] });
  for (const member of triple) cases.push({ member, xyz, args: [-3, 2.5, 0] });
  for (const index of [-1, 0, 1, 2, 3]) cases.push({ member: 'at', xyz, args: [index] });
}
function exercise(Vec3, c) {
  const encode = value => {
    if (value === undefined) return { special: 'undefined' };
    if (typeof value === 'number' && (!Number.isFinite(value) || Object.is(value, -0)))
      return { special: Object.is(value, -0) ? '-0' : String(value) };
    if (Array.isArray(value)) return value.map(encode);
    if (value instanceof Vec3) return { vector: [value.x, value.y, value.z].map(encode) };
    return value;
  };
  const a = new Vec3(...c.xyz), b = c.other && new Vec3(...c.other);
  const before = encode(a), otherBefore = b && encode(b);
  const result = c.member === 'constructor' ? a : a[c.member](...(b ? [b, ...c.args] : c.args));
  return { before, after: encode(a), result: encode(result), same: result === a,
    typed: result instanceof Vec3, otherBefore, otherAfter: b && encode(b) };
}
const expected = cases.map(c => exercise(Vec3, c));
const { build } = plugin('esbuild');
const bundle = await build({ stdin: { resolveDir: dirname(plugin.resolve('vec3')), contents: `import {Vec3} from ${JSON.stringify(plugin.resolve('vec3'))};globalThis.Vec3=Vec3;` },
  bundle: true, write: false, platform: 'browser', format: 'iife', metafile: true });
assert(Object.values(bundle.metafile.outputs).every(o => o.imports.length === 0));
const vm = (await plugin('quickjs-emscripten').getQuickJS()).newContext();
vm.runtime.setMemoryLimit(64 * 1024 * 1024);
vm.runtime.setMaxStackSize(512 * 1024);
const deadline = Date.now() + 10000;
vm.runtime.setInterruptHandler(() => Date.now() > deadline);
function evaluate(code) {
  const result = vm.evalCode(code), handle = result.error ?? result.value;
  const value = vm.dump(handle); handle.dispose();
  if (result.error) throw new Error(JSON.stringify(value));
  return value;
}
try {
  evaluate(bundle.outputFiles[0].text);
  // Preserve -0 inputs; JSON would otherwise erase this distinction.
  const literal = value => Array.isArray(value) ? `[${value.map(literal)}]`
    : value && typeof value === 'object' ? `{${Object.entries(value).map(([k,v]) => `${JSON.stringify(k)}:${literal(v)}`)}}`
      : Object.is(value, -0) ? '-0' : JSON.stringify(value);
  const actual = JSON.parse(evaluate(`JSON.stringify(${literal(cases)}.map(c=>(${exercise})(Vec3,c)))`));
  assert.deepEqual(actual, JSON.parse(JSON.stringify(expected)));
} finally { vm.dispose(); }
const sha = data => createHash('sha256').update(data).digest('hex');
const report = { result: 'passed', cases: cases.length,
  members: [...new Set(['x', 'y', 'z', ...cases.map(c => c.member)])].sort(),
  version: '0.1.10', memoryBytes: 64 * 1024 * 1024, stackBytes: 512 * 1024,
  sourceSha256: sha(await readFile(new URL(import.meta.url))),
  referenceSha256: sha(await readFile(reference.resolve('vec3'))),
  guestSha256: sha(await readFile(plugin.resolve('vec3'))),
  bundleSha256: sha(bundle.outputFiles[0].contents),
  limits: 'Vector values, local mutation, return identity and runtime portability only; no native observation or movement claim.' };
await mkdir(new URL('../../run/mineflayer-reference/', import.meta.url), { recursive: true });
await writeFile(new URL('../../run/mineflayer-reference/vectors-library.json', import.meta.url), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
