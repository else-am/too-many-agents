/**
 * Too Many Agents's Minecraft action helpers, with a scoped Node 18+ HTTP adapter.
 * const mc = createClient((name, args) => harness.callTool(name, args));
 * The callback must resolve to the tool's JSON object, not a harness response envelope.
 * Or use the Node adapter with a scoped connection descriptor:
 * const mc = await connect(descriptorPath);
 * await mc.walk({entity:'player'}); // Tracks the live player until arrival.
 * await mc.mine({position:{x,y,z}}); // Approaches, then breaks using actual inventory/tools.
 * await mc.equip({slot:inventoryIndex,equipment:'mainhand'});
 * await mc.place({position:{x,y,z},face:'up'}); // Click SUPPORTING block with held item.
 * await mc.give({entity:'player',item:'minecraft:oak_log',count:4});
 * const menu = (await mc.menu()).result; // Observe IDs/roles before menuClick.
 * await mc.menuClick({menuId:menu.id,slot,button:0,clickType:'PICKUP'});
 * Helpers return {id,status,terminal,phase,detail?,result?}. Check native result + world.
 * Every action accepts {wait:false} or {timeoutMs,pollIntervalMs} as its second argument.
 * Never repeat a mutation after an unknown outcome; use status(id), wait(id), or cancel(id).
 */
const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

export class ActionError extends Error {
  constructor(action) {
    super(`Minecraft action ${action.id ?? '(unknown)'} ended: ${action.status ?? 'failed'}${action.detail ? ` (${action.detail})` : ''}`);
    this.name = 'ActionError';
    this.actionId = action.id;
    this.action = action;
  }
}

/** The action may still be running. Inspect actionId before deciding what to do next. */
export class ActionPendingError extends Error {
  constructor(action, reason) {
    super(`${reason}; action ${action.id} has an unconfirmed outcome. Inspect its status; do not repeat it.`);
    this.name = 'ActionPendingError';
    this.actionId = action.id;
    this.action = action;
    this.outcome = 'pending';
  }
}

const activeStatuses = new Set(['queued', 'running', 'approaching', 'mining', 'pending', 'in_progress']);
const successfulStatuses = new Set(['completed', 'arrived', 'succeeded']);
const failedStatuses = new Set(['failed', 'interrupted', 'obstructed', 'changed_target', 'target_changed', 'cancelled', 'canceled', 'expired', 'timeout']);

function positive(value, name, maximum) {
  if (!Number.isFinite(value) || value <= 0 || value > maximum) throw new RangeError(`${name} must be greater than zero and at most ${maximum}`);
  return value;
}

/**
 * Adapt a harness's tool caller to the shared action helpers.
 * callTool(name, arguments, {timeoutMs}) must resolve to an ordinary JSON object.
 * A timeout stops waiting, but cannot cancel the harness request. Mutations are never retried.
 */
export function createClient(callTool, { requestTimeoutMs = 15_000 } = {}) {
  if (typeof callTool !== 'function') throw new TypeError('callTool must be a function');
  positive(requestTimeoutMs, 'requestTimeoutMs', 300_000);

  async function tool(name, args = {}, timeoutMs = requestTimeoutMs) {
    positive(timeoutMs, 'timeoutMs', 300_000);
    let timer;
    try {
      const result = await Promise.race([
        Promise.resolve().then(() => callTool(name, args, { timeoutMs })),
        new Promise((_, reject) => {
          timer = setTimeout(() => reject(new Error('Minecraft request timed out; its outcome is unknown. Do not retry a mutation.')), timeoutMs);
        }),
      ]);
      if (!result || typeof result !== 'object' || Array.isArray(result)) {
        throw new TypeError("Minecraft tool caller must return the tool's JSON object");
      }
      return result;
    } finally {
      clearTimeout(timer);
    }
  }

  async function wait(actionOrId, { timeoutMs = 120_000, pollIntervalMs = 200 } = {}) {
    positive(timeoutMs, 'timeoutMs', 3_600_000);
    positive(pollIntervalMs, 'pollIntervalMs', 60_000);
    let state = typeof actionOrId === 'string' ? { id: actionOrId, status: 'pending' } : actionOrId;
    if (!state || typeof state.id !== 'string' || !state.id) throw new Error('An action ID is required to wait');
    const id = state.id;
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      if (successfulStatuses.has(state.status)) return state;
      if (failedStatuses.has(state.status) || state.terminal === true) throw new ActionError(state);
      if (!activeStatuses.has(state.status)) throw new ActionPendingError(state, `Unrecognized action status ${String(state.status)}`);
      const remaining = deadline - Date.now();
      if (remaining <= 0) throw new ActionPendingError(state, 'Wait timed out');
      await delay(Math.min(pollIntervalMs, remaining));
      const timeLeft = deadline - Date.now();
      if (timeLeft <= 0) throw new ActionPendingError(state, 'Wait timed out');
      try { state = await tool('minecraft_action_status', { id }, Math.min(requestTimeoutMs, timeLeft)); }
      catch { throw new ActionPendingError(state, 'Could not read action status'); }
      if (state?.id !== id) throw new ActionPendingError({ id, status: 'pending' }, 'The action status response did not match the requested action');
    }
  }

  async function action(type, args = {}, options = {}) {
    // Validate waits before starting a mutation.
    if (options.timeoutMs !== undefined) positive(options.timeoutMs, 'timeoutMs', 3_600_000);
    if (options.pollIntervalMs !== undefined) positive(options.pollIntervalMs, 'pollIntervalMs', 60_000);
    const started = await tool('minecraft_action', { ...args, type });
    return options.wait === false ? started : wait(started, options);
  }

  const client = {
    tool, action, wait,
    status: id => tool('minecraft_action_status', id ? { id } : {}),
    cancel: id => tool('minecraft_cancel', id ? { id } : {}),
    observe: (args = {}) => tool('minecraft_observe', args),
    blocks: args => tool('minecraft_blocks', args),
    notify: message => tool('notify_user', { message }),
    follow: mode => tool('minecraft_follow', { mode }),
    command: command => tool('minecraft_command', { command }),
    pov: (args = {}) => tool('minecraft_pov', args),
  };
  for (const [method, type] of Object.entries({
    walk: 'walk', look: 'look', mine: 'mine', place: 'place', equip: 'equip',
    creativeItem: 'creative_item', use: 'use', release: 'release', pickup: 'pickup', give: 'give',
    interact: 'interact', menu: 'menu', menuClick: 'menu_click', menuClose: 'menu_close',
  })) client[method] = (args = {}, options = {}) => action(type, args, options);
  return Object.freeze(client);
}

export async function connect(descriptorPath, { requestTimeoutMs = 15_000 } = {}) {
  positive(requestTimeoutMs, 'requestTimeoutMs', 300_000);
  const [{ readFile, writeFile }, { default: http }] = await Promise.all([
    import('node:fs/promises'), import('node:http'),
  ]);
  let info;
  try { info = JSON.parse(await readFile(descriptorPath, 'utf8')); }
  catch { throw new Error('Cannot read the scoped Minecraft connection descriptor'); }
  let address;
  try { address = new URL(info.url); }
  catch { throw new Error('Invalid scoped Minecraft connection descriptor'); }
  if (info.protocol !== 1 || address.protocol !== 'http:' || address.hostname !== '127.0.0.1'
      || address.username || address.password || address.search || address.hash || !['', '/'].includes(address.pathname)
      || typeof info.token !== 'string' || !info.token || /[\r\n]/.test(info.token)
      || typeof info.agentId !== 'string' || !info.agentId || typeof info.session !== 'string' || !info.session) {
    throw new Error('Unsupported scoped Minecraft connection descriptor');
  }
  const { token, session, agentId } = info;

  // node:http connects directly to loopback: no proxy, redirects, or mutation retries.
  function request(payload, timeoutMs = requestTimeoutMs) {
    const body = JSON.stringify({ session, ...payload });
    return new Promise((resolve, reject) => {
      const request = http.request({
        hostname: '127.0.0.1', port: address.port || 80,
        path: '/v1/agent-tool', method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
      }, response => {
        const chunks = [];
        let size = 0;
        response.on('data', chunk => {
          size += chunk.length;
          if (size > 32 * 1024 * 1024) response.destroy(new Error('Minecraft response exceeded 32 MiB'));
          else chunks.push(chunk);
        });
        response.on('error', () => reject(new Error('Minecraft response was interrupted; request outcome is unknown. Do not retry a mutation.')));
        response.on('end', () => {
          let value;
          try { value = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
          catch { reject(new Error(`Invalid Minecraft response (HTTP ${response.statusCode}); request outcome is unknown`)); return; }
          if (response.statusCode < 200 || response.statusCode >= 300) {
            const detail = typeof value.error === 'string' ? `: ${value.error}` : '';
            reject(new Error(`Minecraft rejected the request (HTTP ${response.statusCode})${detail}`));
          } else resolve(value);
        });
      });
      const timer = setTimeout(() => request.destroy(new Error('request timeout')), timeoutMs);
      request.on('error', () => reject(new Error('Minecraft request failed or timed out; its outcome is unknown. Do not retry a mutation.')));
      request.on('close', () => clearTimeout(timer));
      request.end(body);
    });
  }

  const client = createClient(
    (name, args, { timeoutMs }) => request({ tool: name, arguments: args }, timeoutMs),
    { requestTimeoutMs },
  );
  return Object.freeze({
    ...client, agentId, session,
    listTools: () => request({ operation: 'listTools' }),
    async savePov(path, args = {}) {
      const result = await client.pov(args);
      if (typeof result.imageDataUrl !== 'string' || !/^data:image\/png;base64,[A-Za-z0-9+/=]+$/.test(result.imageDataUrl)) {
        throw new Error('POV response did not contain a PNG image');
      }
      await writeFile(path, Buffer.from(result.imageDataUrl.slice('data:image/png;base64,'.length), 'base64'), { flag: 'wx' });
      return { path, width: result.width, height: result.height, capturedAtMs: result.capturedAtMs };
    },
  });
}
