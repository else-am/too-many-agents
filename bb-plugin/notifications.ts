import type { BbPluginApi } from "@get-bb/plugin-sdk";
import type { MinecraftWorlds } from "./minecraft.js";
import { describe, type Thread } from "./protocol.js";
import { agentSpeech, interactionSpeech } from "./speech.js";

/** New replies and questions only; reconnecting never replays old chat. */
export function chatNotifications(bb: BbPluginApi, worlds: MinecraftWorlds, speech: ReturnType<typeof agentSpeech>) {
  const pending = new Map<string, Promise<void>>();
  const openedAt = Date.now();
  let disposed = false;

  function enqueue(threadId: string, work: () => Promise<void>) {
    const next = (pending.get(threadId) ?? Promise.resolve())
      .then(async () => { if (!disposed) await work(); })
      .catch(error => { if (!disposed) bb.log.warn(`Minecraft chat notice: ${describe(error)}`); });
    pending.set(threadId, next);
    void next.finally(() => { if (pending.get(threadId) === next) pending.delete(threadId); });
    return next;
  }

  async function send(thread: Thread, kind: "reply" | "question", id: string, message: string) {
    if (disposed || thread.archivedAt != null || thread.deletedAt != null || !message) return;
    const identity = await worlds.identify(thread.id);
    if (!identity) return;
    let live;
    try { live = worlds.session(identity.worldId); } catch { return; }
    const key = `chat-notice:${thread.id}:${kind}`;
    if (await bb.storage.kv.get<string>(key) === id) return;
    // A lost callback reply must never cause the same chat message to be sent again.
    await bb.storage.kv.set(key, id);
    if (!disposed) await worlds.callback(live, "chat.notice", {
      agentId: identity.agentId, threadId: thread.id, message,
    });
  }

  bb.events.on("thread.idle", ({ thread }) => enqueue(thread.id, async () => {
    if (!await worlds.identify(thread.id)) return;
    const reply = await speech.reply(thread.id);
    if (reply && reply.createdAt >= openedAt) await send(thread, "reply", reply.id, reply.text);
  }));
  bb.events.on("interaction.pending", ({ thread, interaction }) => enqueue(thread.id, async () => {
    const speech = interactionSpeech(interaction);
    if (speech?.kind === "question")
      await send(thread, "question", interaction.id, speech.text || "Question");
  }));
  bb.events.on("thread.deleted", ({ thread }) => enqueue(thread.id, async () => {
    await bb.storage.kv.delete(`chat-notice:${thread.id}:reply`);
    await bb.storage.kv.delete(`chat-notice:${thread.id}:question`);
  }));
  bb.onDispose(() => { disposed = true; });
}
