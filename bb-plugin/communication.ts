import type { BbPluginApi } from "@get-bb/plugin-sdk";
import type { MinecraftWorlds } from "./minecraft.js";
import { describe, type Sdk } from "./protocol.js";

type Input = Awaited<ReturnType<Sdk["threads"]["queuedMessages"]["list"]>>[number]["content"];

function preview(input: Input) {
  return (
    input
      .flatMap((part) =>
        part.type === "text" && part.visibility !== "agent-only" ? [part.text] : [],
      )
      .join(" ")
      .replace(/[\s\u0000-\u001f§]+/g, " ")
      .trim()
      .slice(0, 240) || "[attachment]"
  );
}

/** Display delivered agent messages. BB remains the only sender and queue owner. */
export function communicationNotices(bb: BbPluginApi, worlds: MinecraftWorlds) {
  const pending = new Map<string, Promise<void>>();
  const openedAt = Date.now();
  let disposed = false;

  async function messages(threadId: string) {
    const to = await worlds.identify(threadId);
    if (!to) return;
    let live;
    try {
      live = worlds.session(to.worldId);
    } catch {
      return;
    }
    const key = `communication:${threadId}`;
    let sequence = (await bb.storage.kv.get<number>(key)) ?? 0;
    while (!disposed) {
      const events = await bb.sdk.threads.events.list({
        threadId,
        afterSeq: String(sequence),
        types: ["client/turn/requested"],
        order: "asc",
        limit: "100",
      });
      for (const event of events) {
        if (disposed) return;
        sequence = event.seq;
        // Save before the callback: an unknown outcome must not repeat a notice.
        await bb.storage.kv.set(key, sequence);
        if (event.type !== "client/turn/requested" || event.createdAt < openedAt) continue;
        const message = event.data;
        if (
          message.initiator !== "agent" ||
          message.source !== "tell" ||
          message.retryOfRequestId ||
          !message.senderThreadId
        )
          continue;
        const from = await worlds.identify(message.senderThreadId);
        if (!from || from.worldId !== to.worldId) continue;
        await worlds.callback(live, "communication", {
          senderAgentId: from.agentId,
          senderThreadId: message.senderThreadId,
          recipientAgentId: to.agentId,
          recipientThreadId: threadId,
          message: preview(message.input),
        });
      }
      if (events.length < 100) return;
    }
  }

  bb.events.on("experimental_thread.events", ({ thread }) => {
    const previous = pending.get(thread.id) ?? Promise.resolve();
    const next = previous
      .then(() => messages(thread.id))
      .catch((error) => {
        if (!disposed) bb.log.warn(`Minecraft communication notice: ${describe(error)}`);
      });
    pending.set(thread.id, next);
    void next.finally(() => {
      if (pending.get(thread.id) === next) pending.delete(thread.id);
    });
    return next;
  });
  bb.events.on("thread.deleted", async ({ thread }) => {
    await pending.get(thread.id);
    await bb.storage.kv.delete(`communication:${thread.id}`);
  });
  bb.onDispose(() => {
    disposed = true;
  });
}
