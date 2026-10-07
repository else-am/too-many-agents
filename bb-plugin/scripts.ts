import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import type { BbPluginApi, PluginAgentToolResult } from '@get-bb/plugin-sdk';
import type { MinecraftWorlds } from './minecraft.js';
import { object, describe, type Session } from './protocol.js';
import { runScript } from './scripting/runner.mjs';

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
      heartbeat = (async () => {
        try {
          while (!signal.aborted) {
            await delay(2000, undefined, { signal });
            await call('heartbeat');
          }
        } catch (error) { if (!signal.aborted) controller.abort(error); }
      })();
      const onRequest = async (operation: string, value: unknown, requestSignal: AbortSignal) => {
        const request = object(value);
        if (operation === 'action') {
          const action = await call('action', { action: request }, requestSignal);
          const record: Record<string, unknown> = { id: action.id, type: request.type, lastObserved: action.status };
          actions.push(record);
          if (actions.length > 32) actions.shift();
          let status = action;
          while (status.terminal !== true) {
            await delay(50, undefined, { signal: requestSignal });
            status = await call('status', { id: action.id }, requestSignal);
            record.lastObserved = status.status;
            record.detail = status.detail;
          }
          if (status.status !== 'completed') throw new Error(`Action ${action.id}: ${status.status}: ${status.detail ?? ''}`);
          return { action: status, snapshot: await call('snapshot', {}, requestSignal) };
        }
        if (operation === 'snapshot') return call('snapshot', {}, requestSignal);
        if (operation === 'waitTicks') {
          if (typeof request.ticks !== 'number' || !Number.isSafeInteger(request.ticks) || request.ticks < 0 || request.ticks > 6000)
            throw new Error('ticks must be an integer between 0 and 6000');
          let snapshot = await call('snapshot', {}, requestSignal);
          const target = Number(snapshot.tick) + request.ticks;
          while (Number(snapshot.tick) < target) {
            await delay(50, undefined, { signal: requestSignal });
            snapshot = await call('snapshot', {}, requestSignal);
          }
          return snapshot;
        }
        throw new Error(`Unknown script operation: ${operation}`);
      };
      result = await runScript({
        source: input.code, initial,
        bootstrap: `${bootstrap}\nconst { bot, goals, Vec3 } = MinecraftBot.createBot(JSON.parse(__mcInitial));`,
        workerUrl: pathToFileURL(join(plugin.rootDir, 'scripting/worker.mjs')),
        timeoutMs, signal, onRequest,
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
