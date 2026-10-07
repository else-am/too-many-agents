import type { BbPluginApi } from "@get-bb/plugin-sdk";
import type { Sdk, Thread } from "./protocol.js";

type Interaction = Awaited<ReturnType<Sdk["threads"]["interactions"]["list"]>>[number];
export type Speech = { kind: "approval" | "question" | "working" | "reply"; text: string } | null;

const MAX_TEXT = 280;

/** Markdown to one bounded line of plain text; the conversation keeps the full message. */
function plain(markdown: string) {
  const text = markdown
    .replace(/```[\s\S]*?(```|$)/g, " ")
    .replace(/!\[[^\]]*\]\([^)]*\)/g, " ")
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/^\s*(#+|>|[-*+]|\d+\.)\s+/gm, "")
    .replace(/\*\*|__|`/g, "")
    .replace(/[\s\u0000-\u001f§]+/g, " ")
    .trim();
  return text.length > MAX_TEXT ? text.slice(0, MAX_TEXT - 1).trimEnd() + "…" : text;
}

export function interactionSpeech(interaction: Interaction): Speech {
  const payload = interaction.payload as Record<string, any>;
  if (payload.kind === "approval") {
    const subject = payload.subject ?? {};
    const described = [payload.reason, subject.presentation?.title, subject.command, subject.toolName, subject.tool]
      .find((value) => typeof value === "string" && value.trim());
    return { kind: "approval", text: described ? plain(described) : "" };
  }
  const prompt = payload.kind === "user_question" ? payload.questions?.[0]?.prompt : payload.title;
  return { kind: "question", text: typeof prompt === "string" ? plain(prompt) : "" };
}

/**
 * What an embodied agent is saying: a pending approval or question first, then the latest
 * message of the turn in progress, then an unread final reply.
 */
export function agentSpeech(bb: BbPluginApi) {
  // Each thread's latest top-level assistant message in its latest turn, read incrementally.
  const said = new Map<string, { seq: number; text: string; ended: boolean; replyId: number; completedAt: number }>();

  async function latestMessage(threadId: string) {
    let known = said.get(threadId);
    if (!known) {
      const [start] = await bb.sdk.threads.events.list({ threadId, types: ["turn/started"], order: "desc", limit: "1" });
      known = { seq: start ? start.seq - 1 : 0, text: "", ended: false, replyId: 0, completedAt: 0 };
    }
    for (;;) {
      const events = await bb.sdk.threads.events.list({
        threadId,
        types: ["turn/started", "turn/completed", "item/completed"],
        afterSeq: String(known.seq),
        limit: "100",
      });
      for (const event of events) {
        known.seq = event.seq;
        if (event.type === "turn/started") Object.assign(known, { text: "", ended: false, replyId: 0, completedAt: 0 });
        else if (event.type === "turn/completed") {
          known.ended = true;
          known.replyId = event.data.status === "completed" ? event.seq : 0;
          known.completedAt = event.createdAt;
        }
        else if (event.type === "item/completed" && event.data.item.type === "agentMessage" && !event.data.item.parentToolCallId)
          known.text = event.data.item.text;
      }
      if (events.length < 100) break;
    }
    said.set(threadId, known);
    return known;
  }

  async function speech(thread: Thread, interactions: Interaction[]): Promise<Speech> {
    const pending = interactions.filter((interaction) => interaction.status === "pending");
    const request = pending.find((interaction) => (interaction.payload as { kind?: string }).kind === "approval") ?? pending[0];
    if (request) return interactionSpeech(request);
    if (thread.status === "active") {
      // A thread turns active before its new turn is recorded; the previous turn's reply is stale by then.
      const latest = await latestMessage(thread.id);
      return { kind: "working", text: latest.ended ? "" : plain(latest.text) };
    }
    if (thread.status !== "idle" || thread.latestAttentionAt <= (thread.lastReadAt ?? 0)) return null;
    const final = await reply(thread.id);
    return final ? { kind: "reply", text: final.text } : null;
  }

  async function reply(threadId: string) {
    const latest = await latestMessage(threadId);
    const text = plain(latest.text);
    // A stopped turn or a new turn's commentary is not a final reply.
    return latest.replyId && text ? { id: String(latest.replyId), createdAt: latest.completedAt, text } : null;
  }

  return { speech, reply, forget: (threadId: string) => said.delete(threadId) };
}
