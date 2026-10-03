// Owns the Claude SDK session and forwards Minecraft tool calls.
import { query, getSessionInfo } from '@anthropic-ai/claude-agent-sdk';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { CallToolRequestSchema, ListToolsRequestSchema } from '@modelcontextprotocol/sdk/types.js';
import { createInterface } from 'node:readline';
import { readFile, stat } from 'node:fs/promises';
import { extname } from 'node:path';
import { randomUUID } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createTranslation } from './translation.mjs';
import { buildClaudeCodeModels } from './protocol/model-list.ts';
import { buildClaudeApprovalInteractionPayload, buildClaudeUserQuestionPayload, buildClaudeInteractiveResponse } from './protocol/interactions.ts';
import { claudeUserQuestionInputSchema, toPendingInteractionPermissionProfile } from './protocol/interactive-contract.ts';

const PROTOCOL = 2, GRAMMAR = 3;
const nativeAgentTools = new Set(['Agent', 'Task']);
const sessions = new Map(), calls = new Map();
const cli = process.env.TOO_MANY_AGENTS_CLAUDE;
const write = object => process.stdout.write(JSON.stringify(object) + '\n');
const emit = (s, kind, data, text = '') => write({ method: 'event', params: { threadId: s.id, turnId: s.turn ?? '', kind, text, data } });
// Shared assembly owns implicit turns; Event.turnId is the native lookup alias only.
const delta = (s, value) => emit(s, 'delta', value);
const errorText = error => (error instanceof Error ? error.message : String(error)).slice(0, 2048);
const failure = (category, message, rejected = true) => Object.assign(new Error(message), { category, rejected,
  recovery: ({ unauthorized: 'authRequired', incompatible: 'restartRecommended', session_missing: 'restartRecommended', stale: 'staleTurn',
    unsupported: 'restartRecommended', timeout: 'restartRecommended', disconnected: 'restartRecommended' })[category] ?? 'restartRecommended' });
const bounded = (promise, ms, message) => {
  let timer;
  return Promise.race([promise, new Promise((_, reject) => { timer = setTimeout(() => reject(failure('timeout', message, false)), ms); })]).finally(() => clearTimeout(timer));
};
function closeRequests(s, reason) {
  for (const [id, pending] of calls) if (pending.session === s) {
    calls.delete(id);
    pending.reject(failure('cancelled', reason));
    if (pending.kind === 'interaction') emit(s, 'interaction/resolved', { id, resolution: 'cancelled', status: 'interrupted' });
  }
}
function callHost(s, kind, data, signal) {
  const id = randomUUID();
  return new Promise((resolve, reject) => {
    const abort = () => { if (calls.delete(id)) { reject(failure('cancelled', 'Request cancelled')); if (kind === 'interaction') emit(s, 'interaction/resolved', { id, resolution: 'cancelled', status: 'interrupted' }); } };
    const cleanup = () => signal?.removeEventListener('abort', abort);
    calls.set(id, { resolve: value => { cleanup(); resolve(value); }, reject: error => { cleanup(); reject(error); }, session: s, kind, ...data });
    signal?.addEventListener('abort', abort, { once: true });
    write({ method: kind, params: { id, threadId: s.id, turnId: s.turn, ...data } });
    if (signal?.aborted) abort();
  });
}
function mcp(s, definitions) {
  const known = new Map(definitions.map(tool => [tool.name, tool]));
  const instance = new McpServer({ name: 'too_many_agents', version: '1.0.0' }, { capabilities: { tools: {} } });
  instance.server.setRequestHandler(ListToolsRequestSchema, () => ({ tools: [...known.values()] }));
  instance.server.setRequestHandler(CallToolRequestSchema, async request => {
    if (!known.has(request.params.name) || !s.turn || s.interrupted || s.closed)
      return { content: [{ type: 'text', text: 'Tool call rejected: no active, valid turn.' }], isError: true };
    try {
      const result = await callHost(s, 'toolCall', { name: request.params.name, arguments: request.params.arguments ?? {} });
      // Content blocks are MCP blocks: image bytes remain actual model-visible image content.
      return { content: toolContent(result), isError: result.isError === true || result.ok === false };
    } catch (error) { return { content: [{ type: 'text', text: error.message }], isError: true }; }
  });
  return { type: 'sdk', name: 'too_many_agents', instance };
}
function permissionPayload(name, input, options) {
  if (name === 'AskUserQuestion') return buildClaudeUserQuestionPayload({
    itemId: options.toolUseID, ...claudeUserQuestionInputSchema.parse(input),
  });
  const payload = buildClaudeApprovalInteractionPayload({ toolName: name, itemId: options.toolUseID, input,
    reason: options.title ?? options.decisionReason ?? `Allow ${name}?`,
    permissions: toPendingInteractionPermissionProfile({ toolName: name, input,
      suggestions: options.preventPersistence ? [] : options.suggestions, blockedPath: options.blockedPath }),
  });
  if (options.preventPersistence) payload.availableDecisions = ['allow_once', 'deny'];
  return payload;
}
function encodeAnswer(pending, answer) {
  if (answer.kind !== pending.payload.kind) throw failure('invalid_response', 'Interaction response kind does not match request.');
  if (answer.kind === 'approval' && !pending.payload.availableDecisions.includes(answer.decision))
    throw failure('invalid_response', 'Decision was not offered by Claude.');
  const encoded = buildClaudeInteractiveResponse({ payload: pending.payload, resolution: answer });
  const { kind, ...native } = encoded;
  return native.behavior === 'allow' ? { ...native, updatedInput: native.updatedInput ?? pending.input } : native;
}
function settleTurn(s) {
  if (!s.turn) return;
  closeRequests(s, 'Turn ended');
  s.turn = null;
  s.settled?.(); s.settled = null;
}
function finish(s, status, message) {
  if (!s.turn) return;
  if (message) delta(s, { kind: 'provider.error', message: 'Claude turn ended', detail: message });
  if (status === 'interrupted') s.translation.end();
  else delta(s, { kind: 'turn.boundary', status });
  settleTurn(s);
}
function refreshTitle(s) {
  const nativeId = s.nativeId;
  // This reads existing local metadata; title display never starts another model turn.
  bounded(getSessionInfo(nativeId, { dir: s.options.cwd }), 3000, 'Session metadata timed out.').then(info => {
    if (s.closed || s.nativeId !== nativeId || !info) return;
    const title = info.customTitle || (info.summary !== info.firstPrompt ? info.summary : '');
    if (title && title !== s.lastTitle) {
      s.lastTitle = title;
      delta(s, { kind: 'thread.name', name: title });
    }
  }).catch(() => {});
}
async function contentFor(input) {
  const parts = Array.isArray(input) ? input : input?.content;
  if (!Array.isArray(parts) || !parts.length) throw failure('invalid_input', 'Claude needs common prompt input content.');
  const content = [];
  for (const part of parts) {
    if (part.type === 'text') {
      if (part.mentions?.length) throw failure('unsupported', 'Claude command/file mentions are not supported by this connector.');
      content.push({ type: 'text', text: part.text });
    } else if (part.type === 'localImage') {
      const mime = { '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.webp': 'image/webp' }[extname(part.path).toLowerCase()];
      if (!mime) throw failure('invalid_input', 'Unsupported image format.');
      if ((await stat(part.path)).size > 5 * 1024 * 1024) throw failure('invalid_input', 'Image exceeds 5 MB.');
      const bytes = await readFile(part.path);
      content.push({ type: 'image', source: { type: 'base64', media_type: mime, data: bytes.toString('base64') } });
    } else if (part.type === 'image') {
      const url = part.url ?? part.imageUrl ?? part.dataUrl;
      if (typeof url === 'string' && url.startsWith('data:')) {
        const match = /^data:(image\/[^;]+);base64,(.+)$/s.exec(url);
        if (!match) throw failure('invalid_input', 'Image input must be a base64 image data URL.');
        content.push({ type: 'image', source: { type: 'base64', media_type: match[1], data: match[2] } });
      } else throw failure('unsupported', 'Only embedded image input is supported.');
    } else throw failure('unsupported', `Unsupported Claude input: ${part.type}`);
  }
  return content;
}
function toolContent(result) {
  const content = Array.isArray(result.content) ? [...result.content] : [];
  if (!Array.isArray(result.content)) {
    if (result.imageDataUrl) {
      const match = /^data:(image\/[^;]+);base64,(.+)$/s.exec(result.imageDataUrl);
      if (!match) throw failure('invalid_tool_result', 'POV result did not contain a valid embedded image.');
      content.push({ type: 'image', mimeType: match[1], data: match[2] });
    }
    const { imageDataUrl, ...data } = result;
    if (Object.keys(data).length) content.unshift({ type: 'text', text: JSON.stringify(data) });
  }
  if (content.some(block => block.type === 'image')) content.push({ type: 'text',
    text: 'Image output was delivered to the model; image bytes are unavailable in saved conversation history.' });
  return content.length ? content : [{ type: 'text', text: '{}' }];
}
function nativePermissionMode(permissions) {
  switch (permissions?.permissionMode ?? 'accept-edits') {
    case 'accept-edits': return 'acceptEdits';
    case 'auto': return 'auto';
    case 'full': return 'bypassPermissions';
    default: throw failure('unsupported', 'Unsupported permission mode.');
  }
}
function sandboxSettings(permissions, writableDirectories) {
  const enabled = permissions?.permissionMode !== 'full';
  return { enabled, failIfUnavailable: true, autoAllowBashIfSandboxed: true,
    // Leaving the sandbox still goes through the selected permission reviewer.
    allowUnsandboxedCommands: enabled, filesystem: { allowWrite: writableDirectories ?? [] }, network: { allowLocalBinding: true } };
}
function checkAutoMode(initialized, modelId, permissions) {
  const model = initialized.models.find(model => model.value === modelId || model.resolvedModel === modelId);
  if (permissions?.permissionMode === 'auto' && model && model.supportsAutoMode !== true)
    throw failure('configuration', `${modelId} does not support Approve for me. Choose Accept Edits or a model that supports auto mode.`);
}
// The Claude runtime knows each model's context window; ask it rather than guessing from the model name.
function refreshContextWindow(s) {
  s.query.getContextUsage({ detail: 'summary' })
    .then(usage => { if (!s.closed && usage.maxTokens > 0) s.translation.contextWindow(usage); })
    .catch(() => {});
}
async function start(p, resume = false) {
  if (sessions.size) throw failure('busy', 'Conversation already has an owned session.');
  const s = { id: p.threadId, nativeId: resume ? p.providerSessionId : randomUUID(), turn: null,
    options: p, nativeSubagentsEnabled: p.nativeSubagentsEnabled === true, queue: [], inputWait: null, closed: false, interrupted: false, initialized: false, startupMessage: '' };
  s.settleTurn = () => settleTurn(s);
  s.translation = createTranslation(s, delta, emit);
  const input = { [Symbol.asyncIterator]() { return { next() {
    while (s.queue.length) {
      const item = s.queue.shift();
      if (!item.valid()) { item.reject(failure('stale', 'Claude turn ended before consuming steering input.')); continue; }
      item.consumed(); return Promise.resolve({ value: item.value, done: false });
    }
    if (s.closed) return Promise.resolve({ done: true });
    return new Promise(resolve => { s.inputWait = resolve; });
  }, return() { return Promise.resolve({ done: true }); } }; } };
  const toolServer = mcp(s, p.tools ?? []);
  s.query = query({ prompt: input, options: {
    pathToClaudeCodeExecutable: cli, cwd: p.cwd, additionalDirectories: p.additionalDirectories ?? [], includePartialMessages: true, persistSession: true,
    settingSources: ['user', 'project', 'local'], systemPrompt: { type: 'preset', preset: 'claude_code', append: p.instructions ?? '' },
    ...(resume ? { resume: s.nativeId } : { sessionId: s.nativeId }),
    ...(p.model ? { model: p.model } : {}), ...(p.effort ? { effort: p.effort === 'ultracode' ? 'xhigh' : p.effort } : {}),
    permissionMode: nativePermissionMode(p.permissions),
    sandbox: sandboxSettings(p.permissions, p.writableDirectories),
    ...(p.permissions?.permissionMode === 'full' ? { allowDangerouslySkipPermissions: true } : {}),
    mcpServers: { too_many_agents: toolServer }, allowedTools: (p.tools ?? []).map(t => `mcp__too_many_agents__${t.name}`),
    hooks: { PreToolUse: [{ hooks: [async input => {
      if (input.hook_event_name === 'PreToolUse' && nativeAgentTools.has(input.tool_name) && !s.nativeSubagentsEnabled)
        return { continue: true, hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: 'deny',
          permissionDecisionReason: 'Native sub-agents are disabled. Use the too_many_agents child-agent tools.' } };
      return {};
    }] }] },
    canUseTool: async (name, input, options) => {
      const payload = permissionPayload(name, input, options);
      try { return await callHost(s, 'interaction', { payload, input, suggestions: options.suggestions ?? [] }, options.signal); }
      catch { return { behavior: 'deny', message: 'Request cancelled because its turn or connection ended.', interrupt: true }; }
    },
    extraArgs: { 'replay-user-messages': null },
    env: { ...process.env, CLAUDE_CODE_STARTUP_FAILURE_RESULTS: '1' },
    stderr: () => {},
  } });
  sessions.set(s.id, s);
  refreshContextWindow(s);
  s.done = (async () => {
    try {
      for await (const message of s.query) {
        if (!s.initialized && message.type === 'result' && message.is_error) {
          s.startupMessage = (Array.isArray(message.errors) ? message.errors.filter(e => typeof e === 'string').join('\n') : typeof message.result === 'string' ? message.result : '').slice(0, 2048);
        }
        if (message.session_id && message.session_id !== s.nativeId) {
          const previous = s.nativeId;
          finish(s, 'interrupted', 'Claude replaced its native session.');
          s.nativeId = message.session_id;
          emit(s, 'session/replaced', { previousProviderSessionId: previous, providerSessionId: s.nativeId, contextLost: true });
          delta(s, { kind: 'thread.identity', providerThreadId: s.nativeId });
        }
        try { s.translation.message(message); }
        catch { delta(s, { kind: 'provider.warning', summary: `Malformed Claude ${message.type} entry isolated.`, category: 'general' }); }
        if (message.type === 'result') { refreshTitle(s); refreshContextWindow(s); }
      }
      if (!s.closed && s.initialized) {
        finish(s, 'failed', 'Claude stream ended unexpectedly.');
        s.translation.end();
        emit(s, 'session/disconnected', { recovery: { kind: 'restartRecommended' }, retryable: false, rejected: false }, 'Claude session ended; reconnect to resume context.');
      }
    } catch (error) {
      if (!s.initialized) s.startupMessage ||= error instanceof Error ? error.message.slice(0, 2048) : '';
      if (!s.closed && s.initialized) {
        finish(s, 'failed', 'Claude stream disconnected.'); s.translation.end();
        emit(s, 'session/disconnected', { recovery: { kind: 'restartRecommended' }, retryable: false, rejected: false },
          `Claude process stopped; the action outcome may be unknown.${error instanceof Error ? ' ' + error.message.slice(0, 2048) : ''}`);
      }
    } finally {
      s.closed = true; s.inputWait?.({ done: true }); closeRequests(s, 'Claude session closed');
      s.translation.end();
      for (const queued of s.queue.splice(0)) queued.reject(failure('disconnected', 'Claude ended before consuming input.'));
    }
  })();
  try {
    const initialized = await bounded(s.query.initializationResult(), 25000, 'Claude initialization timed out.');
    if (s.closed) throw failure('initialization', s.startupMessage || 'Claude ended during initialization.');
    checkAutoMode(initialized, p.model, p.permissions);
    // Startup can silently fall back; verify the requested mode before accepting work.
    await s.query.setPermissionMode(nativePermissionMode(p.permissions));
    s.initialized = true;
  } catch (error) {
    await release(s);
    throw failure(error.category ?? 'initialization', `Claude session setup failed: ${s.startupMessage || errorText(error)}`, true);
  }
  delta(s, { kind: 'thread.identity', providerThreadId: s.nativeId });
  if (resume) refreshTitle(s);
  // A new SDK session has no native transcript until its first input is submitted.
  return { providerSessionId: s.nativeId, restorable: resume };
}
function push(s, text, uuid, accepted = () => {}, valid = () => true) {
  return new Promise((resolve, reject) => {
    const value = { type: 'user', session_id: s.nativeId, parent_tool_use_id: null, uuid, message: { role: 'user', content: text } };
    if (s.closed) return reject(failure('disconnected', 'Session is closed.'));
    if (!valid()) return reject(failure('stale', 'Claude turn ended before consuming steering input.'));
    const consumed = () => { accepted(); resolve(); };
    if (s.inputWait) { const receiver = s.inputWait; s.inputWait = null; receiver({ value, done: false }); consumed(); }
    else s.queue.push({ value, consumed, reject, valid });
  });
}
async function interrupt(s) {
  if (!s.turn) { if (s.sending) s.interrupted = true; return; }
  s.interrupted = true; closeRequests(s, 'Turn interrupted');
  const settled = new Promise(resolve => { s.settled = resolve; });
  try { await bounded(s.query.interrupt(), 5000, 'Claude interrupt acknowledgement timed out.'); await bounded(settled, 8000, 'Claude interrupt settlement timed out.'); }
  catch { finish(s, 'interrupted', 'Claude was stopped before a final result; action outcomes may be unknown.'); await release(s); }
}
async function release(s) {
  if (!s.closed) {
    s.interrupted = true; closeRequests(s, 'Session released');
    finish(s, 'interrupted', 'Session released.');
    s.closed = true; s.inputWait?.({ done: true }); s.inputWait = null;
    s.translation.end();
    s.query.close();
    await bounded(s.done ?? Promise.resolve(), 5000, 'Claude shutdown timed out.').catch(() => {});
  }
  sessions.delete(s.id);
}
let catalog;
async function dispatch(method, p) {
  if (method === 'hello') {
    if (p.protocol !== PROTOCOL || p.grammar !== GRAMMAR) throw failure('incompatible', 'Claude helper protocol/grammar mismatch. Rebuild Too Many Agents.');
    if (!cli) throw failure('not_installed', 'TOO_MANY_AGENTS_CLAUDE must point to the installed Claude executable.');
    return { protocol: PROTOCOL, grammar: GRAMMAR, sdk: '0.3.283', node: process.versions.node };
  }
  if (method === 'catalog') {
    if (catalog && !p.refresh && catalog.expires > Date.now()) return catalog.value;
    let auth;
    try { const result = await promisify(execFile)(cli, ['auth', 'status'], { timeout: 10000 }); auth = JSON.parse(result.stdout); }
    catch (error) { try { auth = JSON.parse(error.stdout); } catch { throw failure('not_installed', 'Claude authentication status could not be read.'); } }
    if (auth.loggedIn !== true) throw failure('unauthorized', 'Claude is not signed in. Run claude auth login, then reconnect.');
    const probe = query({ prompt: '.', options: { pathToClaudeCodeExecutable: cli, cwd: p.cwd ?? process.cwd(), maxTurns: 0, persistSession: false, settingSources: ['user', 'project', 'local'], stderr: () => {} } });
    try {
      const init = await bounded(probe.initializationResult(), 25000, 'Claude catalog discovery timed out.');
      const discovered = buildClaudeCodeModels(init.models);
      const models = [...discovered.models, ...discovered.selectedOnlyModels].map(descriptor => {
        const native = init.models.find(m => (m.resolvedModel ?? m.value) === descriptor.id || m.value === descriptor.id);
        return { id: descriptor.id, label: descriptor.displayName, descriptor, supportsAutoMode: native ? native.supportsAutoMode === true : null,
          efforts: descriptor.supportedReasoningEfforts.map(e => e.reasoningEffort), defaultEffort: descriptor.defaultReasoningEffort,
          serviceTiers: [{ id: 'default', name: 'Standard', description: 'Normal usage' },
            ...(native?.supportsFastMode ? [{ id: 'fast', name: 'Fast', description: 'Claude fast mode' }] : [])] };
      });
      const value = { models, auth: { authenticated: true, method: auth.authMethod } };
      catalog = { value, expires: Date.now() + 60000 }; return value;
    } finally { probe.close(); }
  }
  if (method === 'usage') {
    const probe = query({ prompt: '.', options: { pathToClaudeCodeExecutable: cli, maxTurns: 0,
      persistSession: false, settingSources: ['user'], stderr: () => {} } });
    try {
      await bounded(probe.initializationResult(), 25000, 'Claude usage discovery timed out.');
      const read = probe.usage_EXPERIMENTAL_MAY_CHANGE_DO_NOT_RELY_ON_THIS_API_YET;
      if (typeof read !== 'function') return { windows: [], message: 'Usage unavailable in this Claude version' };
      const report = await bounded(read.call(probe, { skipBehaviors: true }), 15000, 'Claude usage read timed out.');
      const windows = [], limits = report.rate_limits;
      const add = (label, value) => {
        if (!value) return;
        const resetsAtMs = value.resets_at ? Date.parse(value.resets_at) : NaN;
        windows.push({ label, usedPercent: Number.isFinite(value.utilization) ? value.utilization : null,
          ...(Number.isFinite(resetsAtMs) ? { resetsAtMs } : {}) });
      };
      for (const [key, label] of [['five_hour', '5 hours'], ['seven_day', '7 days'],
        ['seven_day_oauth_apps', 'Apps - 7 days'], ['seven_day_opus', 'Opus - 7 days'], ['seven_day_sonnet', 'Sonnet - 7 days']]) add(label, limits?.[key]);
      for (const row of limits?.model_scoped ?? []) add(`${row.display_name} - 7 days`, row);
      if (limits?.extra_usage?.is_enabled) add('Extra usage', limits.extra_usage);
      return { windows, message: windows.length ? '' : 'No usage limits reported' };
    } finally { probe.close(); }
  }
  if (method === 'start' || method === 'resume') return start(p, method === 'resume');
  if (method === 'respond') {
    const pending = calls.get(p.requestId);
    if (!pending) throw failure('stale', 'Request is no longer active.');
    const value = pending.kind === 'interaction' ? encodeAnswer(pending, p.answer) : p.result;
    calls.delete(p.requestId); pending.resolve(value);
    if (pending.payload?.subject?.kind === 'plan' && p.answer?.decision === 'allow_once') {
      void pending.session.query.setPermissionMode(nativePermissionMode(pending.session.options.permissions)).catch(() =>
        delta(pending.session, { kind: 'provider.warning', category: 'config', summary: 'Claude could not leave Plan mode; choose a permission mode before the next turn.' }));
    }
    if (pending.kind === 'interaction') emit(pending.session, 'interaction/resolved', { id: p.requestId, resolution: 'answered', answer: p.answer });
    return {};
  }
  const s = sessions.get(p.threadId);
  if (!s) throw failure('session_missing', 'Claude session is not connected; resume it explicitly.');
  if (method === 'release') { await release(s); return {}; }
  if (method === 'interrupt') { await interrupt(s); return {}; }
  if (method === 'permissions') {
    // Takes effect immediately, including for the running turn's next tool call.
    checkAutoMode(await s.query.initializationResult(), s.options.model, p.permissions);
    await s.query.setPermissionMode(nativePermissionMode(p.permissions));
    s.options.permissions = p.permissions;
    return {};
  }
  if (method === 'steer') {
    const content = await contentFor(p.input);
    if (s.closed || s.interrupted || !s.turn || s.turn !== p.expectedTurnId || s.sending)
      throw failure('stale', 'Claude no longer has the expected active turn.');
    const turn = s.turn;
    // A steer uses the running turn's settings and native lookup alias.
    await bounded(push(s, content, randomUUID(), () => s.translation.accept(p.requestId),
      () => !s.closed && !s.interrupted && s.turn === turn), 10000, 'Claude did not consume steering input; delivery outcome is unknown.');
    return { turnId: turn };
  }
  if (method === 'send') {
    const content = await contentFor(p.input);
    if (s.closed || s.turn || s.sending) throw failure('busy', 'Claude session is closed or already has an active turn.');
    s.sending = true; s.interrupted = false;
    try {
    let setting = 'model';
    try {
      if (p.model !== s.options.model) {
        await s.query.setModel(p.model || undefined);
        s.options.model = p.model;
      }
      setting = 'approval mode';
      await s.query.setPermissionMode(nativePermissionMode(p.permissions));
      setting = 'effort and speed settings';
      await s.query.applyFlagSettings({ effortLevel: p.effort === 'ultracode' ? 'xhigh' : p.effort || null, fastMode: p.serviceTier === 'fast' });
    } catch (error) {
      throw failure('configuration', `Claude could not apply ${setting}: ${errorText(error)} No input was sent.`, true);
    }
    if (s.interrupted || s.closed) throw failure('cancelled', 'Turn cancelled before input was sent.');
    s.options.permissions = p.permissions;
    s.nativeSubagentsEnabled = p.nativeSubagentsEnabled === true;
    s.options.model = p.model; s.options.effort = p.effort;
    refreshContextWindow(s);
    s.turn = randomUUID();
    const turn = s.turn;
    s.translation.accept(p.requestId);
    delta(s, { kind: 'turn.open' });
    try { await bounded(push(s, content, randomUUID()), 10000, 'Claude did not consume input; delivery outcome is unknown.'); }
    catch (error) { finish(s, 'failed', error.message); throw error; }
    return { turnId: turn };
    } finally { s.sending = false; }
  }
  throw failure('unsupported', `Unsupported Claude operation: ${method}`);
}
const input = createInterface({ input: process.stdin, crlfDelay: Infinity });
input.on('line', line => {
  let request;
  try { request = JSON.parse(line); } catch { return; }
  void Promise.resolve().then(() => dispatch(request.method, request.params ?? {})).then(
    result => write({ id: request.id, result }),
    error => write({ id: request.id, error: { category: error.category ?? 'unknown', rejected: error.rejected === true, recovery: error.recovery ?? 'restartRecommended', message: `Claude ${request.method} failed: ${errorText(error)}` } }),
  );
});
async function shutdown() { await Promise.all([...sessions.values()].map(release)); process.exit(0); }
input.on('close', shutdown);
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
