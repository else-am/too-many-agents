# Minecraft–BB local protocol v2

Plugin id `minecraft`, installed BB 0.45.0 or newer, SDK 0.6.15. Minecraft owns
agents, bodies and each agent's BB thread link; BB owns everything about the
conversation. The plugin stores only a delivered-message cursor per thread in BB plugin storage.

## Minecraft → plugin

Minecraft reads BB's address (`serverUrl`) from BB's own `~/.bb/bb-app-runtime.json`.
One JSON endpoint:
`POST /api/v1/plugins/minecraft/http/v1/rpc`, with BB's default local check:
browser origins are refused, as on BB's own API. Request `{op, ...arguments}`; response
`{ok:true,result}` or `{ok:false,error:{code,message}}`. Mutations are sent
once. A lost response has an unknown outcome; callers inspect state and never
automatically resend.

- `session.attach`: `{worldId,worldSessionId,callbackUrl,callbackToken}`.
  Idempotent; Minecraft repeats it every few seconds so a restarted plugin
  learns the world again. `callbackUrl` must be literal loopback HTTP.
  `session.detach`: `{worldId,worldSessionId}`.
- BB passthroughs, returning native payloads: `catalog`, `projects`,
  `system.config`, `system.defaultProvider.set`, `project.executionOptions`,
  `environment.providers`, `usage`, `backend.status`,
  `project.create/configure/remove` (the create host `local` resolves to BB's
  primary host), `project.source.update` `{projectId,sourceId,path}`.
- A spawn environment `{environmentProviderId:"project-checkout"}` works in the
  project's own folder: the plugin reuses that folder's existing environment, or
  creates it on the folder's machine. Minecraft uses this for each world's
  project, whose folder is `<save>/too-many-agents/workspace`.
- `threads.read`: `{threadIds}` → `{[threadId]: {thread,executionOptions,
  interactions,queuedMessages} | {deleted:true} | {error}}`. `agent.read` adds `timeline`.
- `agent.start`: `{worldId,agentId,minecraftAccess,nonce,spawn: BB ThreadSpawnArgs}` starts an agent's thread with its first message.
- Thread operations take `{threadId}` plus BB's own arguments: `agent.send`
  `{send}`, `agent.update` `{patch}`, `agent.markRead`, `agent.stop`,
  `agent.archive`, `agent.unarchive`, `timeline` `{query?}`,
  `timeline.summary` `{query}`, `interaction.resolve` `{interactionId,
  resolution}`, `interaction.respond` `{interactionId,value}`,
  `interaction.cancel` `{interactionId}`, and `queue.create/update/delete/send/
  reorder` `{args}`.

## Plugin → Minecraft

`POST callbackUrl` with `Authorization: Bearer callbackToken`. Body `{protocol:2,
op,worldId,worldSessionId,requestId,expiresAt,...arguments}`; reply
`{ok:true,result}`. Java rejects a stale world session, and rejects a
physical request after `expiresAt` without touching the world.

- `agents` → `{agents:[{agentId,name,threadId,projectId,
  minecraftAccess,settings}]}` for coordination checks.
- `changed`: `{agentId,threadId,bind?,nonce?}`. The plugin pushes it on each BB event
  for an agent's thread; Java rereads that thread. `bind` links a thread the
  plugin started to an agent that has none yet, including when the start
  request's reply was lost. Java persists its nonce before requesting the start;
  binding must match that nonce. On reconnect, the plugin reads its own BB
  threads and metadata to recover missed bindings without repeating a spawn.
- `tool`: `{agentId,threadId,tool,arguments}` → BB tool result.
  `spawn_agent`: `{agentId,threadId,request}` creates a body and BB thread through
  the same Java path as the in-game UI. Each must come from the acting agent's
  own thread.
- `communication`: `{senderAgentId,senderThreadId,recipientAgentId,
  recipientThreadId,message}` displays a delivered BB message. Java checks both
  body/thread associations and the player's display setting. This never sends
  another message to BB.
- `cancel`: `{cancelRequestId}`. Sent when BB aborts a tool call (request
  cancelled, thread stopped or deleted, plugin disposed). Java stops that
  request's actions, including one whose cancel arrives first.
- `world_metadata` returns Java's world record for station queries.
  `notify`: `{agentId,message}` shows a player notice.

Thread plugin metadata `{worldId,agentId,minecraftAccess,nonce}` decides which
tools BB offers, but any client can write it, so it grants nothing: Java checks
every physical request against the agent's own thread.

`bb minecraft spawn` uses BB's public CLI builder and thread SDK. It adds body
creation; the plugin does not replace BB's other coordination commands. Parent
and lifecycle-owner IDs are native BB fields, and omitting a parent creates an
independent thread. BB 0.45 only supports `startedOnBehalfOf` for forks, so the
initial prompt explicitly identifies agent delegation rather than human
approval. BB owns child completion and attention notifications.

BB lifecycle events prompt a fresh thread read; periodic sync catches missed
events. Java cancels physical actions on stop, saves and suspends archived bodies,
restores them on unarchive, and retires deleted bodies. Only a definite missing
thread response clears a link; connection failures do not delete bodies.
