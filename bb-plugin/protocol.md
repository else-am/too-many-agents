# Minecraft–BB local protocol v2

Plugin id `minecraft`, installed BB 0.45.0 or newer, SDK 0.6.15. Minecraft owns
agents, bodies and each agent's BB thread link; BB owns everything about the
conversation. The plugin keeps no durable state.

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
  interactions,queuedMessages} | {error}}`. `agent.read` adds `timeline`.
- `agent.start`: `{worldId,agentId,minecraftAccess,spawn: BB ThreadSpawnArgs,
  parentThreadId?}` starts an agent's thread with its first message.
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

- `agents` → `{agents:[{agentId,name,threadId,parentAgentId,projectId,
  minecraftAccess,settings}]}` for coordination checks.
- `changed`: `{agentId,threadId,bind?}`. The plugin pushes it on each BB event
  for an agent's thread; Java rereads that thread. `bind` links a thread the
  plugin started to an agent that has none yet, including when the start
  request's reply was lost.
- `tool`: `{agentId,threadId,tool,arguments}` → BB tool result.
  `spawn_body`: `{parentAgentId,threadId,name,projectId,minecraftAccess,
  settings,stationId?}` → `{agentId,body}`. `remove_body`: `{agentId,
  parentAgentId,threadId}` removes the caller's own child and frees its station.
  Each must come from the acting agent's own thread.
- `cancel`: `{cancelRequestId}`. Sent when BB aborts a tool call (request
  cancelled, thread stopped or deleted, plugin disposed). Java stops that
  request's actions, including one whose cancel arrives first.
- `world_metadata` returns Java's world record for station queries.
  `notify`: `{agentId,message}` shows a player notice.

Thread plugin metadata `{worldId,agentId,minecraftAccess,nonce}` decides which
tools BB offers, but any client can write it, so it grants nothing: Java checks
every physical request against the agent's own thread.

Independent children use native `parentThreadId` and inherit the parent's
execution options. BB 0.45 only supports `startedOnBehalfOf` for forks, so their
initial input explicitly identifies parent delegation rather than human
approval. Child idle, failure and attention notices go to the parent as
attributed messages.
