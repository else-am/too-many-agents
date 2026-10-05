# Minecraft–BB local protocol v3

Plugin id `minecraft`, BB 0.45.0 or newer, SDK 0.6.15. The plugin owns BB
request preparation, project/workspace selection, conversation creation and
lifecycle interpretation. Java owns bodies, saved world records, physical
execution and native rendering. Both UI and CLI creation use `agents.ts`.

## Minecraft → plugin

Minecraft reads `serverUrl` from BB's `~/.bb/bb-app-runtime.json` and calls
`POST /api/v1/plugins/minecraft/http/v1/rpc`. BB's default local check refuses
browser origins. Requests are `{op,worldId,worldSessionId,...arguments}`;
responses are `{ok:true,result}` or `{ok:false,error:{code,message}}`.
Mutations are sent once. A lost response has an unknown outcome; inspect state
before any retry.

- `session.attach`: `{protocol:3,worldId,worldSessionId,callbackUrl,callbackToken}`.
  Idempotent and repeated periodically to reconnect after plugin reload.
  The callback URL must be literal loopback HTTP. `session.detach` takes the
  world and session IDs.
- `world.sync` reconciles associated BB threads and returns display-ready
  project rows. The plugin creates a world's BB project lazily, using the
  save's workspace folder, and resolves BB environments. Java saves its ID.
- `agent.create`: `{request}` combines body choices with BB execution choices.
  The plugin separates these, creates a body with an opaque saved draft, and
  starts a conversation only when there is initial input. The UI's optional
  `worktree` boolean is translated here; CLI native environments stay native.
- `role.list` returns complete roles from plugin KV (`role:<name>`).
  `role.save`: `{role:{name,instructions?,bb,body},agentId?}` saves the UI draft;
  with an agent it captures current native BB execution choices and worktree state.
  Omitted instructions preserve an existing role's instructions.
  Java never persists role records. Instructions for an unstarted body are copied
  into plugin KV (`instructions:<agentId>`), prepended at first start, then removed.
- `agent.message`: `{agentId,message:{text,images?,pointing?,delivery?,...}}`.
  The plugin prepares native input and image uploads, starts the first thread
  or sends to the existing one. `agent.settings` takes `{agentId,settings}` and
  separates physical settings from native BB updates or an unstarted draft.
- `agent.archive`: `{agentId,archived}`; `agent.stop`, `agent.markRead`:
  `{agentId}`. `agent.queue.steer/cancel`: `{agentId,messageId}`.
- Native UI data and actions: `catalog`, `system.config`,
  `system.defaultProvider.set`, `project.executionOptions`, `usage`,
  `backend.status`, `timeline`, `timeline.summary`, `interaction.resolve`,
  `interaction.respond`, `interaction.cancel`, `chat.asset`, `chat.open`.
  Thread-specific operations resolve the body's `agentId` inside the plugin.
  Native `threadId` requests remain available for development diagnostics.
  Java renders these payloads; it does not orchestrate BB calls.
- `project.creationOptions`: `{projectId}` → `{worktreeAvailable}`.
  `project.create`: `{name,folder}`; `project.configure`: `{projectId,name}`;
  `project.remove`: `{projectId}`. Host/source/provider details stay in the plugin.

## Plugin → Minecraft

`POST callbackUrl` uses `Authorization: Bearer callbackToken`. Body:
`{protocol:3,op,worldId,worldSessionId,requestId,expiresAt,...arguments}`.
Java rejects stale sessions and expires physical requests before execution.

- `agents` returns saved body associations, physical settings and opaque drafts.
- `body.create`: `{settings,projectId,minecraftAccess,draft}` creates and saves
  a physical body. CLI calls also carry the acting `agentId` and `threadId`;
  Java checks ownership, physical permission inheritance and cancellation.
- `body.validate`: `{settings}` validates only reusable body fields and spawnable
  entity types on the game thread, without adding entities. Role saves use this.
- `body.parent`: `{agentId,threadId,parentThreadId}` refreshes the plugin's trusted
  BB parent association in memory before a station command.
- `station.edit`: `{agentId,threadId,request}` uses the same caller/session/expiry/
  cancellation checks as physical tools. Java derives the caller project and owned
  bodies, then checks project/occupancy on the server thread with the mutation.
  `request.operation` is `station-create/update/assign/delete`; corners are `min`
  and `max` block arrays. Missing dimension uses the caller body's dimension.
  Assignment defaults to self; blank `request.agentId` releases only self/children.
  This does not alter the user's unrestricted in-game station editing path.
- `body.begin`: `{agentId,nonce}` persists a start nonce before BB creation.
  `body.bind`: `{agentId,threadId,nonce}` accepts only that saved nonce. On
  reconnect the plugin finds its own BB threads by metadata, recovers missed
  bindings, and never repeats creation. A pending unknown start is not retried.
- `body.draft`: `{agentId,patch}` saves opaque BB choices for an unstarted body.
  `body.settings`: `{agentId,settings}` applies physical settings.
- `body.sync`: `{agentId,threadId,view?,state?,running?,projectId?}`. `view` is the
  plugin's ready-to-display agent state. `state` is `present`, `suspended` or
  `deleted`. Java cancels physical actions when `running` is false, drops inventory
  and saves the empty body before suspension, restores it on return, and releases
  stations on suspension/deletion. BB lifecycle fields are interpreted only in the plugin.
  The view passes through BB's `status`, `latestAttentionAt` and `lastReadAt` for
  native UI indicators. `hasPendingInteraction` comes from the pending-interaction
  list; `queuedWork` is `failed` if any queued message has a non-null `failureReason`,
  otherwise `waiting` if any are queued, or `none`. Its one computed
  `activity` drives body behavior: `wants_you` for pending input, error or unread
  attention first; otherwise `working` for active/pending/starting/stopping;
  otherwise `idle`. Java uses that value directly.
- `world_metadata`, `world.workspace`, `world.project` expose the saved world
  record, create its owned workspace directory, and persist the BB project ID.
- `tool`: `{agentId,threadId,tool,arguments}` executes only for the body's own
  BB thread. `cancel`: `{cancelRequestId}` cancels a physical request, even if
  cancellation arrives first.
- `communication`: `{senderAgentId,senderThreadId,recipientAgentId,
  recipientThreadId,message}` displays a delivered BB message. Java checks both
  associations and the player's display preference; it never sends another BB
  message. The plugin persists a delivered-message cursor to avoid replay.

Thread metadata `{worldId,agentId,minecraftAccess,nonce}` controls tool offering,
but grants no authority: Java validates every physical request. Connection
failures do not delete bodies; only definite BB deletion does.

`bb minecraft spawn` uses BB's public CLI builder and SDK. Parent and lifecycle
owner are native BB fields; omitting a parent creates an independent thread.
BB 0.45 supports `startedOnBehalfOf` only for forks, so the initial prompt
identifies agent delegation rather than human approval. All other coordination
uses ordinary BB commands. BB owns queues, child results and attention notices.

Roles expose `bb minecraft role list/show/create/update/delete`. Create/update use
field flags, matching BB's provider/model/reasoning/service-tier/permission-mode
names. Updates merge only supplied fields; behaviors replace the entire behavior
object. CLI creation/deletion and update writes are serialized. `spawn --role`
copies both halves, then applies explicit flags. Java validates physical settings
and prohibits delegated cross-project spawns. Body `mode` is `survival`,
`creative`, or `creative_commands`; only the last permits world commands.
Both Creative modes permit Creative physical actions. Delegated bodies cannot
gain either capability beyond their caller. Saved data has no old-format conversions.
