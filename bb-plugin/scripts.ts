import { createHash, randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import type { BbPluginApi, PluginAgentToolResult } from '@get-bb/plugin-sdk';
import type { MinecraftWorlds } from './minecraft.js';
import { ApiError, object, describe, type Session } from './protocol.js';
import { runScript } from './scripting/runner.mjs';
import { createItemDecoder, createItemEncoder, encodeItemTransport, decodeItemTransport } from './scripting/item-wire.mjs';

export function minecraftScripts(bb: BbPluginApi, worlds: MinecraftWorlds) {
  const running = new Map<string, AbortController>();
  bb.onDispose(() => { for (const controller of running.values()) controller.abort(new Error('Minecraft plugin stopped')); });

  return async function execute(
    args: unknown, ctx: { threadId: string; signal: AbortSignal }, live: Session, agentId: string,
  ): Promise<PluginAgentToolResult> {
    const input = object(args);
    if (typeof input.code !== 'string') throw new Error('code must be JavaScript source');
    const timeoutMs = input.timeoutMs ?? 120_000;
    if (typeof timeoutMs !== 'number' || !Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 300_000)
      throw new Error('timeoutMs must be between 1 and 300000');
    const key = `${live.worldSessionId}:${agentId}`;
    if (running.has(key) || running.size >= 4) throw new Error('A script is already running for this body, or the script runner is full');
    const controller = new AbortController();
    running.set(key, controller);
    const signal = AbortSignal.any([ctx.signal, controller.signal]);
    const scriptId = randomUUID();
    const started = performance.now();
    const timer = setTimeout(() => controller.abort(new Error('Script deadline exceeded')), timeoutMs);
    const actions: Array<Record<string, unknown>> = [];
    const observeAction = (state: Record<string, unknown>) => {
      const record = actions.find(action => action.id === state.id);
      if (!record) return;
      // An action reply can arrive before an older queued stream frame.
      if (state.status === 'running' && record.lastObserved !== 'running') return;
      record.lastObserved = state.status;
      record.detail = state.detail;
      // Keep verified edits even when the final action reply or world is lost.
      if (record.type === 'route') record.progress = state.result ?? state.progress;
    };
    let bridgeOperations = 0;
    let began = false;
    let heartbeat: Promise<void> | undefined;
    let result: unknown;
    let failure: unknown;
    let release = 'not_started';
    const call = async (operation: string, values: Record<string, unknown> = {}, callSignal = signal) => {
      bridgeOperations++;
      return object(await worlds.toolCallback(live, { threadId: ctx.threadId, signal: callSignal }, 'script', {
        agentId, arguments: { ...values, operation, scriptId },
      }), 'script response');
    };
    try {
      const plugin = (await bb.sdk.plugins.list()).plugins.find(item => item.id === bb.pluginId);
      if (!plugin) throw new Error('Minecraft plugin directory is unavailable');
      const bootstrap = await readFile(join(plugin.rootDir, 'dist/scripting/bot.js'), 'utf8');
      // If begin loses its reply, release is still attempted once for this ID.
      began = true;
      const initial = await call('begin', { timeoutMs });
      const decodeItem = createItemDecoder(initial.itemRegistries);
      const encodeItem = createItemEncoder(initial.itemRegistries);
      // Repeated equipment/menu/stream copies usually contain identical bytes.
      // Bound both entry count and retained bytes for large books or nested items.
      const itemCache = new Map<string, { value: ReturnType<typeof encodeItemTransport>; bytes: number; key: string }>();
      let itemCacheBytes = 0;
      const prepareSnapshot = (snapshot: Record<string, unknown>) => {
        observeAction(object(snapshot.action));
        const hands = object(snapshot.hands), menu = object(hands.menu);
        const entries = [...hands.inventory as unknown[], ...Object.values(object(hands.equipment)),
          ...menu.slots as unknown[], menu.carried];
        if (menu.merchant) {
          for (const value of object(menu.merchant).offers as unknown[]) {
            const offer = object(value);
            entries.push(offer.baseCostA, offer.costA, offer.costB, offer.result);
          }
        }
        const eventEntities = ((snapshot.entityEvents ?? []) as unknown[])
          .flatMap(value => object(value).entities as unknown[]);
        for (const value of [snapshot.body, ...snapshot.entities as unknown[], ...eventEntities]) {
          const entity = object(value);
          if (entity.equipment) entries.push(...entity.equipment as unknown[]);
          if (entity.droppedItem) entries.push(entity.droppedItem);
        }
        for (const value of entries) {
          const entry = object(value);
          if (typeof entry.wire !== 'string') throw new Error('Native item wire is missing');
          const wire = entry.wire;
          let cached = itemCache.get(wire);
          if (!cached) {
            const value = encodeItemTransport(decodeItem(wire));
            const bytes = Buffer.byteLength(wire) + Buffer.byteLength(JSON.stringify(value));
            cached = { value, bytes, key: createHash('sha256').update(wire).digest('hex') };
            if (bytes <= 8 * 1024 * 1024) {
              while (itemCache.size >= 128 || itemCacheBytes + bytes > 8 * 1024 * 1024) {
                const oldest = itemCache.keys().next().value!;
                itemCacheBytes -= itemCache.get(oldest)!.bytes;
                itemCache.delete(oldest);
              }
              itemCache.set(wire, cached);
              itemCacheBytes += bytes;
            }
          }
          entry.item = cached.value;
          entry.itemKey = cached.key;
          delete entry.wire;
        }
        return snapshot;
      };
      prepareSnapshot(initial);
      heartbeat = (async () => {
        try {
          while (!signal.aborted) {
            await delay(2000, undefined, { signal });
            await call('heartbeat');
          }
        } catch (error) { if (!signal.aborted) controller.abort(error); }
      })();
      const startAction = async (request: Record<string, unknown>, requestSignal: AbortSignal) => {
        if (request.type === 'creative_slot') {
          // Guest values are data only; registry remapping/encoding stays trusted.
          try {
            const { item, ...rest } = request;
            request = { ...rest, wire: encodeItem(decodeItemTransport(item)) };
          } catch (error) {
            throw Object.assign(new Error(`Invalid creative item: ${describe(error)}`), { code: 'minecraft_action_rejected_before_start' });
          }
        }
        try {
          const action = await call('action', { action: request }, requestSignal);
          if (typeof action.id !== 'string' || action.status !== 'running' || !Number.isInteger(action.sequence))
            throw new Error('Invalid native action acknowledgement; outcome is unknown');
          actions.push({ id: action.id, type: request.type, lastObserved: action.status });
          if (actions.length > 32) actions.shift();
          return action;
        } catch (error) {
          // Native pre-start rejection is known. A lost/malformed reply may
          // hide an accepted action: stop the whole script and release its lease.
          if (error instanceof ApiError && error.code === 'minecraft_action_failed')
            throw Object.assign(new Error(error.message), { code: 'minecraft_action_rejected_before_start' });
          controller.abort(error);
          throw error;
        }
      };
      const awaitAction = async (id: unknown, requestSignal: AbortSignal) => {
        const record = actions.find(action => action.id === id);
        if (!record) throw new Error('Action does not belong to this execution');
        try {
          const status = await call('awaitAction', { id }, requestSignal);
          if (status.id !== id || !Number.isInteger(status.sequence)
              || !['completed', 'failed', 'interrupted', 'timeout'].includes(String(status.status)))
            throw new Error('Invalid native terminal acknowledgement; outcome is unknown');
          observeAction(status);
          return status;
        } catch (error) {
          // Without the terminal barrier, catching goto must not allow the
          // body to keep working unobserved under a renewed lease.
          controller.abort(error);
          throw error;
        }
      };
      const onRequest = async (operation: string, value: unknown, requestSignal: AbortSignal) => {
        const request = object(value);
        if (operation === 'startAction') return startAction(request, requestSignal);
        if (operation === 'awaitAction') return awaitAction(request.id, requestSignal);
        if (operation === 'action') return awaitAction((await startAction(request, requestSignal)).id, requestSignal);
        if (operation === 'cancelAction' || operation === 'stopRoute') {
          if (!actions.some(action => action.id === request.id)) throw new Error('Action does not belong to this execution');
          try { return await call(operation === 'cancelAction' ? 'cancel' : 'stopRoute', { id: request.id }, requestSignal); }
          catch (error) {
            // A missing stop acknowledgement must not permit a replacement
            // route to start while the old route might still control the body.
            controller.abort(error);
            throw error;
          }
        }
        throw new Error(`Unknown script operation: ${operation}`);
      };
      result = await runScript({
        source: input.code, initial,
        bootstrap: `${bootstrap}\nconst { bot, goals, Vec3, Movements, Block, Item, Entity, ChatMessage, MessageBuilder, BossBar, ChunkColumn, Particle, Recipe, RecipeItem, update, drainControls } = MinecraftBot.createBot(JSON.parse(__mcInitial));
          function __mcUpdate(payload) { update(JSON.parse(payload), true); }
          async function __mcFinish() { await drainControls?.(); }`,
        workerUrl: pathToFileURL(join(plugin.rootDir, 'scripting/worker.mjs')),
        timeoutMs, signal, onRequest,
        onUpdates: async (send, streamSignal) => {
          bridgeOperations++;
          await worlds.toolCallback(live, { threadId: ctx.threadId, signal: streamSignal }, 'script', {
            agentId, arguments: { operation: 'stream', scriptId },
          }, value => send(prepareSnapshot(object(value))));
          if (!streamSignal.aborted) throw new Error('Minecraft state stream closed while the script was running');
        },
      });
    } catch (error) { failure = error; }
    finally {
      clearTimeout(timer);
      controller.abort(new Error('Script stopped'));
      await heartbeat;
      if (began) {
        try { await call('end', {}, AbortSignal.timeout(3000)); release = 'confirmed'; }
        catch { release = 'unconfirmed; the lease expires without heartbeats'; }
      }
      running.delete(key);
    }
    const report = {
      scriptId, ...(failure ? { error: describe(failure) } : { result }),
      ...(failure instanceof Error && 'requests' in failure ? {
        execution: {
          requests: failure.requests,
          completedRequests: (failure as Error & { completedRequests?: number }).completedRequests,
          updates: (failure as Error & { updates?: number }).updates,
          outstandingRequests: (failure as Error & { outstandingRequests?: number }).outstandingRequests,
          logs: (failure as Error & { logs?: string[] }).logs,
        },
      } : {}),
      elapsedMs: Math.round(performance.now() - started), bridgeOperations,
      bodyRelease: release, recentActions: actions,
      ...(failure ? { outcome: 'Stopped. Completed mutations remain. Last observed action states may be stale; no automatic retry was attempted.' } : {}),
    };
    return { content: [{ type: 'text', text: JSON.stringify(report) }], ...(failure ? { isError: true } : {}) };
  };
}
