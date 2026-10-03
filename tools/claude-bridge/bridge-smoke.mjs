// Live checks against the bundled bridge and installed Claude Code, with disposable sessions.
import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { randomUUID } from 'node:crypto';
import { mkdir, readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// TOO_MANY_AGENTS_CLAUDE, else `claude` on PATH.
function claudeCli() {
  return process.env.TOO_MANY_AGENTS_CLAUDE ?? execFileSync('which', ['claude'], { encoding: 'utf8' }).trim();
}

const cwd = process.argv[2];
if (!cwd || resolve(cwd) !== cwd) throw new Error('Pass an absolute disposable project directory.');
await mkdir(cwd, { recursive: true });
const child = spawn(process.execPath, [fileURLToPath(new URL('./dist/bridge.mjs', import.meta.url))], {
  env: { ...process.env, TOO_MANY_AGENTS_CLAUDE: claudeCli() },
  stdio: ['pipe', 'pipe', 'inherit'],
});
const pending = new Map(), events = [];
createInterface({ input: child.stdout }).on('line', line => {
  const packet = JSON.parse(line);
  if (packet.method) events.push(packet);
  else pending.get(packet.id)?.(packet);
});
child.on('exit', code => {
  for (const finish of pending.values()) finish({ error: { message: `Bridge exited (${code})` } });
});
async function rpc(method, params = {}) {
  const id = randomUUID();
  let timeout;
  try {
    return await new Promise((resolve, reject) => {
      timeout = setTimeout(() => reject(new Error(`${method} timed out; delivery is unknown`)), 45000);
      pending.set(id, resolve);
      child.stdin.write(JSON.stringify({ id, method, params }) + '\n');
    });
  } finally { clearTimeout(timeout); pending.delete(id); }
}
async function ok(method, params) {
  const result = await rpc(method, params);
  assert.ok(!result.error, JSON.stringify(result.error));
  return result.result;
}
async function completed(after) {
  const deadline = Date.now() + 90000;
  while (Date.now() < deadline) {
    const end = events.slice(after).find(e => e.params?.data?.kind === 'turn.boundary');
    if (end) { assert.equal(end.params.data.status, 'completed', JSON.stringify(events.slice(after))); return; }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error('Claude turn did not finish; do not replay its input.');
}
const threadId = randomUUID();
try {
  await ok('hello', { protocol: 2, grammar: 3 });
  const catalog = await ok('catalog', { cwd });
  const unsupported = catalog.models.find(m => m.supportsAutoMode === false);
  const supported = catalog.models.find(m => m.supportsAutoMode === true && /sonnet/.test(m.id))
    ?? catalog.models.find(m => m.supportsAutoMode === true);
  assert.ok(unsupported && supported, 'Catalog must expose native auto-mode compatibility');
  const base = { threadId, cwd, instructions: 'Run only the requested integration check.', tools: [],
    model: supported.id, effort: 'low', serviceTier: 'default', permissions: { permissionMode: 'auto' } };
  const rejected = await rpc('start', { ...base, model: unsupported.id });
  assert.equal(rejected.error?.rejected, true);
  assert.match(rejected.error.message, /auto|Approve for me/i);
  assert.ok(!events.some(e => e.params?.data?.kind === 'input.accepted'));
  console.log('PASS unsupported auto mode rejected before input with an actionable error');

  await ok('start', base);
  const beforeFailure = events.length;
  const badSetting = await rpc('send', { ...base, model: unsupported.id, requestId: randomUUID(), input: [{ type: 'text', text: 'This input must not run.' }] });
  assert.equal(badSetting.error?.rejected, true);
  assert.match(badSetting.error.message, /approval mode/i);
  assert.ok(!events.slice(beforeFailure).some(e => ['input.accepted', 'turn.open'].includes(e.params?.data?.kind)));
  console.log('PASS configuration rejection is explicit and does not start a turn');

  const nonce = randomUUID(), after = events.length;
  const input = [{ type: 'text', text: `Use Bash to run: printf '%s' '${nonce}' > bridge-smoke.txt\nThen reply with CHECK-DONE. Do not delegate or do anything else.` }];
  await ok('send', { ...base, requestId: randomUUID(), input });
  await completed(after);
  assert.equal(await readFile(resolve(cwd, 'bridge-smoke.txt'), 'utf8'), nonce);
  const identity = events.find(e => e.params?.data?.kind === 'thread.identity').params.data.providerThreadId;
  console.log('PASS real auto-mode turn executes requested tool after a rejected configuration');

  await ok('release', { threadId });
  const resumed = await ok('resume', { ...base, providerSessionId: identity, permissions: { permissionMode: 'accept-edits' } });
  assert.equal(resumed.providerSessionId, identity);
  const resumeAfter = events.length;
  await ok('send', { ...base, permissions: { permissionMode: 'accept-edits' }, requestId: randomUUID(),
    input: [{ type: 'text', text: 'Repeat the nonce from the printf command in our previous turn. Do not use tools.' }] });
  await completed(resumeAfter);
  assert.ok(JSON.stringify(events.slice(resumeAfter)).includes(nonce), 'Resumed conversation lost the previous turn');
  console.log('PASS same conversation resumes with changed permissions and remembers prior work');
  await ok('release', { threadId });
} finally { child.stdin.end(); }
