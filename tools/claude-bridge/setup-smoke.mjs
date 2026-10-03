// Real SDK integration probe. Its project and native sessions are disposable.
import { query, createSdkMcpServer, tool } from '@anthropic-ai/claude-agent-sdk';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';

// TOO_MANY_AGENTS_CLAUDE, else `claude` on PATH.
function claudeCli() {
  return process.env.TOO_MANY_AGENTS_CLAUDE ?? execFileSync('which', ['claude'], { encoding: 'utf8' }).trim();
}
const cli = claudeCli();
const cwd = process.argv[2];
if (!cwd) throw new Error('Pass an absolute disposable project directory.');
const base = { pathToClaudeCodeExecutable: cli, cwd, settingSources: [], persistSession: false, stderr: () => {} };
const probe = query({ prompt: '.', options: { ...base, maxTurns: 0 } });
let model;
try {
  const initialized = await probe.initializationResult();
  const models = initialized.models;
  if (!models?.length) throw new Error('No models discovered');
  model = models.find(m => /haiku/i.test(m.value))?.value ?? models[0].value;
  console.log(JSON.stringify({ phase: 'catalog', modelCount: models.length, selectedModel: model }));
} finally { probe.close(); }
const secret = randomUUID();
let toolCalls = 0;
const server = createSdkMcpServer({ name: 'setup', version: '1.0.0', tools: [tool('probe', 'Return the setup verification nonce. Call once.', {}, async () => {
  toolCalls++;
  return { content: [{ type: 'text', text: `The setup nonce is ${secret}. Reply with this nonce exactly.` }] };
})] });
const first = query({ prompt: 'Call mcp__setup__probe exactly once, then reply with the returned nonce and nothing else.', options: {
  ...base, model, persistSession: true, maxTurns: 3,
  mcpServers: { setup: server }, allowedTools: ['mcp__setup__probe'],
} });
let sessionId, answer = '', success = false;
try {
  for await (const message of first) {
    if (message.session_id) sessionId = message.session_id;
    if (message.type === 'result') { answer = message.result ?? ''; success = message.subtype === 'success' && !message.is_error; }
  }
} finally { first.close(); }
if (!success || toolCalls !== 1 || !answer.includes(secret) || !sessionId) throw new Error(`Tool roundtrip failed: success=${success}, toolCalls=${toolCalls}, nonceMatched=${answer.includes(secret)}`);
console.log(JSON.stringify({ phase: 'model_and_mcp', success: true, toolCalls }));
const resumed = query({ prompt: 'What was the setup nonce you just returned? Repeat it exactly, with no tool calls.', options: {
  ...base, model, persistSession: true, resume: sessionId, maxTurns: 1, tools: [],
} });
let resumedAnswer = '', resumedId, resumedSuccess = false;
try {
  for await (const message of resumed) {
    if (message.session_id) resumedId = message.session_id;
    if (message.type === 'result') { resumedAnswer = message.result ?? ''; resumedSuccess = message.subtype === 'success' && !message.is_error; }
  }
} finally { resumed.close(); }
if (!resumedSuccess || resumedId !== sessionId || !resumedAnswer.includes(secret)) throw new Error(`Resume failed: success=${resumedSuccess}, sameSession=${resumedId === sessionId}, contextRemembered=${resumedAnswer.includes(secret)}`);
console.log(JSON.stringify({ phase: 'resume', success: true, sameSession: true, contextRemembered: true }));
