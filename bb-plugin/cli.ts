import { cliCommand, defineCli, PluginCliError, type BbPluginApi, type PluginCliContext } from "@get-bb/plugin-sdk";
import { isAbsolutePath, pathStyle } from "./paths.js";
import type { MinecraftAgents } from "./agents.js";
import type { MinecraftWorlds } from "./minecraft.js";
import { object, type SpawnOptions, type ObjectValue } from "./protocol.js";

const text = (description: string) => ({
  type: "string" as const,
  description,
});

/** BB owns parsing, help, errors and thread creation. These options only translate CLI names to SDK fields. */
export function registerMinecraftCli(
  bb: BbPluginApi,
  worlds: MinecraftWorlds,
  agents: MinecraftAgents,
) {
  const json = (value: unknown) => ({ exitCode: 0, stdout: JSON.stringify(value) });
  async function callerLocation(ctx: PluginCliContext) {
    if (ctx.threadId) {
      const thread = await bb.sdk.threads.get({ threadId: ctx.threadId });
      if (!thread.environmentId) throw new PluginCliError("The calling thread has no environment to resolve its machine.");
      const environment = await bb.sdk.environments.get({ environmentId: thread.environmentId });
      return { hostId: environment.hostId, cwd: ctx.cwd ?? environment.path };
    }
    const { primaryHostId } = await bb.sdk.system.config();
    if (!primaryHostId) throw new PluginCliError("BB's local host is unavailable. Pass the text directly instead.");
    return { hostId: primaryHostId, cwd: ctx.cwd };
  }
  async function readTextFile(file: string, flag: string, ctx: PluginCliContext) {
    if (file === "-") throw new PluginCliError(`${flag} - is not supported by BB's plugin CLI. Use a file path or pass the text directly.`);
    const { hostId, cwd } = await callerLocation(ctx);
    if (!isAbsolutePath(file) && (!cwd || !isAbsolutePath(cwd)))
      throw new PluginCliError(`${flag} needs an absolute path when the invoking directory is unavailable.`);
    const path = isAbsolutePath(file) ? file : pathStyle(cwd!).resolve(cwd!, file);
    const result = await bb.sdk.files.read({ hostId, path, signal: ctx.signal });
    return result.contentEncoding === "base64"
      ? new TextDecoder("utf-8", { fatal: true }).decode(Buffer.from(result.content, "base64"))
      : result.content;
  }
  const coordinates = (value: string) => {
    const parts = value.split(",");
    if (parts.length !== 3 || parts.some(part => !/^-?\d+$/.test(part.trim()) || Math.abs(Number(part)) > 30_000_000))
      throw new PluginCliError("Corners must be block coordinates: x,y,z.");
    return parts.map(Number);
  };
  async function station(threadId: string | undefined, signal: AbortSignal | undefined, request: ObjectValue) {
    if (!threadId) throw new PluginCliError("Run from a Minecraft-associated thread.");
    const { live, agent } = await worlds.caller(threadId);
    // Refresh the trusted BB parent relationship; Java decides which bodies the caller owns.
    for (const body of await worlds.agents(live))
      if (body.threadId && !body.removed && !body.archived && body.projectId === agent.projectId) {
        const thread = await bb.sdk.threads.get({ threadId: body.threadId });
        await worlds.callback(live, "body.parent", { agentId: body.agentId, threadId: body.threadId, parentThreadId: thread.parentThreadId ?? "" });
      }
    return json(await worlds.toolCallback(live, { threadId, signal: signal ?? AbortSignal.timeout(120_000) }, "station.edit", {
      agentId: agent.agentId, request,
    }));
  }
  const stationId = [{ name: "id", description: "Station ID.", required: true }] as const;
  const roleName = [{ name: "name", description: "Role name.", required: true }] as const;
  function roleCommand(mode: "create" | "update") {
    return cliCommand({
      summary: mode === "create" ? "Create a role in BB; fails if it exists." : "Update only the supplied role fields; fails if missing.",
      positionals: roleName,
      options: {
        provider: text("BB provider ID."), model: text("BB model ID."),
        reasoning: { type: "enum", values: ["none", "low", "medium", "high", "xhigh", "ultracode", "max", "ultra"], description: "Reasoning level." },
        "service-tier": text("Provider service tier."),
        "permission-mode": { type: "enum", values: ["accept-edits", "auto", "full"], description: "Permission mode; delegated spawns cannot exceed their caller." },
        worktree: { type: "boolean", description: "Use a new worktree." },
        "no-worktree": { type: "boolean", description: "Use the project checkout." },
        body: text("Minecraft body type."), mode: { type: "enum", values: ["survival", "creative", "creative_commands"], description: "Game mode." },
        "minecraft-access": { type: "boolean", description: "Enable physical tools." },
        "no-minecraft-access": { type: "boolean", description: "Disable physical tools." },
        behaviors: text("Behavior object as JSON; replaces all behavior choices."),
        "instructions-file": text("UTF-8 file on the calling thread's host (BB's local host outside a thread); relative to the invoking directory."),
        instructions: { ...text("Instructions text; --instructions-stdin reads one line on the invoking machine."), stdin: true },
        json: { type: "boolean", description: "Print JSON." },
      },
      constraints: [
        { kind: "at-most-one", options: ["worktree", "no-worktree"] },
        { kind: "at-most-one", options: ["minecraft-access", "no-minecraft-access"] },
        { kind: "at-most-one", options: ["instructions", "instructions-file"] },
      ],
      async run({ options: o, positionals: p }, ctx) {
        const live = await worlds.validationSession(ctx.threadId);
        const choices: ObjectValue = {}, body: ObjectValue = {};
        for (const [flag, key] of [["provider", "providerId"], ["model", "model"], ["reasoning", "reasoningLevel"], ["service-tier", "serviceTier"], ["permission-mode", "permissionMode"]] as const)
          if (o[flag] !== undefined) choices[key] = o[flag]!;
        if (o.worktree || o["no-worktree"]) choices.worktree = !o["no-worktree"];
        if (o.body !== undefined) body.body = o.body;
        if (o.mode !== undefined) body.mode = o.mode;
        if (o["minecraft-access"] || o["no-minecraft-access"]) body.minecraftAccess = !o["no-minecraft-access"];
        if (o.behaviors !== undefined) body.behaviors = object(JSON.parse(o.behaviors));
        const instructions = o.instructions ?? (o["instructions-file"] !== undefined
          ? await readTextFile(o["instructions-file"], "--instructions-file", ctx) : undefined);
        return json(await agents.roleSave(live, { name: p.name, bb: choices, body, ...(instructions !== undefined ? { instructions } : {}) }, mode));
      },
    });
  }
  bb.cli.register(
    defineCli({
      name: "minecraft",
      summary: "Create embodied BB agents and inspect their world.",
      commands: {
        "station create": cliCommand({
          summary: "Create a station inside your body's project.",
          options: { name: { ...text("Station label."), required: true }, from: { ...text("First corner: x,y,z."), required: true }, to: { ...text("Second corner: x,y,z."), required: true }, dimension: text("Defaults to your body's dimension.") },
          run({ options: o }, ctx) {
            return station(ctx.threadId, ctx.signal, { operation: "station-create", label: o.name, min: coordinates(o.from), max: coordinates(o.to), ...(o.dimension ? { dimension: o.dimension } : {}) });
          },
        }),
        "station update": cliCommand({
          summary: "Update a station in your body's project.",
          positionals: stationId,
          options: { name: text("Station label."), from: text("First corner: x,y,z."), to: text("Second corner: x,y,z."), dimension: text("Dimension for the new corners.") },
          constraints: [{ kind: "requires", option: "from", needs: ["to"] }, { kind: "requires", option: "to", needs: ["from"] }, { kind: "requires", option: "dimension", needs: ["from"] }],
          run({ options: o, positionals: p }, ctx) {
            return station(ctx.threadId, ctx.signal, { operation: "station-update", stationId: p.id, ...(o.name ? { label: o.name } : {}), ...(o.from && o.to ? { min: coordinates(o.from), max: coordinates(o.to) } : {}), ...(o.dimension ? { dimension: o.dimension } : {}) });
          },
        }),
        "station assign": cliCommand({
          summary: "Assign a free station to yourself or a child body; --unassign releases it.",
          positionals: stationId,
          options: { agent: text("Body ID; defaults to yourself."), unassign: { type: "boolean", description: "Unassign only your own or a child's station." } },
          constraints: [{ kind: "at-most-one", options: ["agent", "unassign"] }],
          run({ options: o, positionals: p }, ctx) {
            return station(ctx.threadId, ctx.signal, { operation: "station-assign", stationId: p.id, ...(o.unassign ? { agentId: "" } : o.agent ? { agentId: o.agent } : {}) });
          },
        }),
        "station delete": cliCommand({
          summary: "Delete an unoccupied station in your body's project.",
          positionals: stationId,
          run({ positionals: p }, ctx) { return station(ctx.threadId, ctx.signal, { operation: "station-delete", stationId: p.id }); },
        }),
        "role list": cliCommand({
          summary: "List roles stored in BB.",
          options: { json: { type: "boolean", description: "Print JSON." } },
          async run() { return json(await agents.roleList()); },
        }),
        "role show": cliCommand({
          summary: "Show a complete role record.", positionals: roleName,
          options: { json: { type: "boolean", description: "Print JSON." } },
          async run({ positionals: p }) { return json(await agents.roleGet(p.name)); },
        }),
        "role create": roleCommand("create"),
        "role update": roleCommand("update"),
        "role delete": cliCommand({
          summary: "Delete a role; existing agents are unchanged.", positionals: roleName,
          options: { json: { type: "boolean", description: "Print JSON." } },
          async run({ positionals: p }) { await agents.roleDelete(p.name); return json({ deleted: p.name }); },
        }),
        spawn: cliCommand({
          summary: "Create a BB thread with a Minecraft body in your current world.",
          description:
            "Omit --parent-self/--parent-thread for an independent thread. Never repeat a spawn after an unknown outcome; inspect bodies first. Other thread operations use bb thread.",
          options: {
            prompt: {
              ...text("First task; --prompt-stdin reads one line on the invoking machine."),
              stdin: true,
            },
            "prompt-file": text("UTF-8 task file on the calling thread's host; relative to the invoking directory. Preserves newlines."),
            project: text("BB project ID; defaults to the calling thread's project."),
            provider: text("BB provider ID; omitted execution options use BB defaults."),
            model: text("BB model ID."),
            "reasoning-level": {
              type: "enum",
              values: ["none", "low", "medium", "high", "xhigh", "max", "ultra", "ultracode"],
              description: "Provider reasoning level.",
            },
            "service-tier": text("Provider service tier."),
            "permission-mode": {
              type: "enum",
              values: ["accept-edits", "auto", "full"],
              description: "BB permission mode; cannot exceed the caller's mode.",
            },
            title: text("BB thread title."),
            "parent-self": {
              type: "boolean",
              description: "Create a child of the calling BB thread.",
            },
            "parent-thread": text("Explicit BB parent thread ID."),
            "lifecycle-owner-thread": text(
              "Archive/delete the thread with this BB lifecycle owner.",
            ),
            visibility: {
              type: "enum",
              values: ["visible", "hidden"],
              description: "BB thread visibility.",
            },
            section: text("BB section ID."),
            pinned: { type: "boolean", description: "Pin the new BB thread." },
            environment: text("Existing BB environment ID, or absolute unmanaged workspace path."),
            "new-environment": {
              type: "enum",
              values: ["personal", "worktree"],
              description: "Create a BB environment.",
            },
            "base-branch": text("Starting Git ref for --new-environment worktree."),
            machine: {
              ...text("Existing BB machine ID for the new environment."),
              aliases: ["host"],
            },
            "environment-provider": text("BB environment provider ID."),
            "environment-inputs": text("JSON inputs for the environment provider."),
            name: text("Minecraft display name; generated when omitted."),
            role: text("Role to copy from BB storage."),
            mode: { type: "enum", values: ["survival", "creative", "creative_commands"], description: "Minecraft game mode." },
            "minecraft-access": { type: "enum", values: ["true", "false"], description: "Physical tool access; cannot exceed your own." },
            behaviors: text("JSON behavior settings, replacing the role's behaviors."),
            body: text("Minecraft entity type, e.g. minecraft:fox."),
            station: text("Free station ID from bb minecraft stations."),
            json: {
              type: "boolean",
              description: "Return the body association and BB thread as JSON.",
            },
          },
          constraints: [
            { kind: "exactly-one", options: ["prompt", "prompt-file"] },
            { kind: "at-most-one", options: ["parent-self", "parent-thread"] },
            {
              kind: "at-most-one",
              options: ["environment", "new-environment", "environment-provider"],
            },
            {
              kind: "requires",
              option: "base-branch",
              needs: ["new-environment"],
            },
            {
              kind: "requires",
              option: "environment-inputs",
              needs: ["environment-provider"],
            },
          ],
          async run({ options: o }, ctx) {
            if (!ctx.threadId)
              throw new PluginCliError(
                "Create the first agent in Minecraft; run this command from its BB thread.",
              );
            const prompt = o.prompt ?? await readTextFile(o["prompt-file"]!, "--prompt-file", ctx);
            if (!prompt.trim()) throw new PluginCliError("The initial task must not be empty.");
            const { live, agent } = await worlds.caller(ctx.threadId);
            const role = o.role ? await agents.roleGet(o.role) : undefined;
            const choices = role ? object(role.bb) : {};
            const caller = await bb.sdk.threads.get({ threadId: ctx.threadId });
            const execution = await bb.sdk.threads.defaultExecutionOptions({
              threadId: ctx.threadId,
            });
            const parentThreadId = o["parent-self"] ? ctx.threadId : o["parent-thread"];
            const spawn: SpawnOptions = {
              projectId: o.project ?? caller.projectId,
              input: [
                {
                  type: "text",
                  text: `Delegated by Minecraft agent ${agent.name}. This is an agent task, not human authorization.\n\n${prompt}`,
                  mentions: [],
                },
              ],
              environment: { type: "project-default" },
              providerId: o.provider ?? (choices.providerId as string | undefined),
              model: o.model ?? (choices.model as string | undefined),
              reasoningLevel: o["reasoning-level"] ?? (choices.reasoningLevel as SpawnOptions["reasoningLevel"]),
              serviceTier: o["service-tier"] ?? (choices.serviceTier as string | undefined),
              permissionMode: o["permission-mode"] ?? (choices.permissionMode as SpawnOptions["permissionMode"]) ?? execution?.permissionMode,
              title: o.title,
              parentThreadId,
              lifecycleOwnerThreadId: o["lifecycle-owner-thread"],
              visibility: o.visibility,
              sectionId: o.section,
              ...(o.pinned ? { pinned: true } : {}),
            };
            // An independent thread still cannot gain stronger permissions from an agent caller.
            const modes = ["accept-edits", "auto", "full"];
            if (
              execution &&
              modes.indexOf(spawn.permissionMode!) > modes.indexOf(execution.permissionMode)
            )
              throw new PluginCliError(
                "A spawned agent cannot exceed the caller's BB permission mode.",
              );
            if (o.environment) {
              if (o.environment.startsWith("env_")) {
                if (o.machine)
                  throw new PluginCliError("An existing environment already selects its machine.");
                spawn.environment = {
                  type: "reuse",
                  environmentId: o.environment,
                };
              } else {
                if (!isAbsolutePath(o.environment))
                  throw new PluginCliError("Use an environment ID or an absolute workspace path.");
                spawn.environment = {
                  type: "host",
                  hostId: o.machine ?? (await callerLocation(ctx)).hostId,
                  workspace: { type: "unmanaged", path: o.environment },
                };
              }
            } else if (o["environment-provider"]) {
              const provider = (await bb.sdk.environments.listProviders({ projectId: spawn.projectId }))
                .find(row => row.id === o["environment-provider"]);
              if (!provider) throw new PluginCliError(`Unknown environment provider: ${o["environment-provider"]}`);
              let inputs = o["environment-inputs"] === undefined ? null : JSON.parse(o["environment-inputs"]);
              if (provider.inputs !== null && inputs === null) {
                if (!provider.acceptsEmptyInputs) throw new PluginCliError(`The '${provider.id}' environment provider needs --environment-inputs <json>.`);
                inputs = {};
              }
              if (provider.inputs === null && inputs !== null)
                throw new PluginCliError(`The '${provider.id}' environment provider takes no --environment-inputs.`);
              if (provider.machineProviderId && o.machine)
                throw new PluginCliError("This environment provider chooses its own machine; omit --machine.");
              spawn.environment = {
                type: "provider",
                environmentProviderId: provider.id,
                inputs,
                ...(!provider.machineProviderId ? { machine: { type: "existing", hostId: o.machine ?? (await callerLocation(ctx)).hostId } } : {}),
              };
            } else if (o["new-environment"] === "worktree") {
              spawn.environment = {
                type: "host",
                hostId: o.machine ?? (await callerLocation(ctx)).hostId,
                workspace: {
                  type: "managed-worktree",
                  baseBranch: o["base-branch"]
                    ? { kind: "named", name: o["base-branch"] }
                    : { kind: "default" },
                },
              };
            } else if (o["new-environment"] === "personal") {
              if (o["base-branch"])
                throw new PluginCliError("--base-branch requires --new-environment worktree.");
              spawn.environment = {
                type: "host",
                hostId: o.machine ?? (await callerLocation(ctx)).hostId,
                workspace: { type: "personal" },
              };
            } else if (o.machine) {
              const project = await bb.sdk.projects.get({ projectId: spawn.projectId });
              spawn.environment = {
                type: "host",
                hostId: o.machine,
                workspace: project.kind === "personal" ? { type: "personal" } : { type: "unmanaged", path: null },
              };
            }
            const result = await agents.create(
              live,
              {
                ...(role ? object(role.body) : {}),
                ...spawn,
                ...(role ? { roleInstructions: role.instructions } : {}),
                ...(!o.environment && !o["new-environment"] && !o["environment-provider"] && !o.machine && typeof choices.worktree === "boolean" ? { worktree: choices.worktree } : {}),
                ...(o.mode ? { mode: o.mode } : {}),
                ...(o["minecraft-access"] ? { minecraftAccess: o["minecraft-access"] === "true" } : {}),
                ...(o.behaviors ? { behaviors: object(JSON.parse(o.behaviors)) } : {}),
                name: o.name,
                ...(o.body ? { body: o.body } : {}),
                stationId: o.station,
              } as unknown as ObjectValue,
              { threadId: ctx.threadId, signal: ctx.signal ?? AbortSignal.timeout(120_000) },
            );
            return {
              exitCode: 0,
              stdout: o.json
                ? JSON.stringify(result)
                : `Created ${result.name}: @thread:${result.threadId}\nBody: ${result.agentId}`,
            };
          },
        }),
        bodies: cliCommand({
          summary: "List bodies and their BB thread IDs in your Minecraft world.",
          options: { json: { type: "boolean", description: "Print JSON." } },
          async run(_input, ctx) {
            if (!ctx.threadId) throw new PluginCliError("Run from a Minecraft-associated thread.");
            const { live } = await worlds.caller(ctx.threadId);
            return {
              exitCode: 0,
              stdout: JSON.stringify(await worlds.agents(live)),
            };
          },
        }),
        stations: cliCommand({
          summary: "List Minecraft stations in your BB project.",
          options: { json: { type: "boolean", description: "Print JSON." } },
          async run(_input, ctx) {
            if (!ctx.threadId) throw new PluginCliError("Run from a Minecraft-associated thread.");
            const { live, agent } = await worlds.caller(ctx.threadId);
            const metadata = object(await worlds.callback(live, "world_metadata", {}));
            const stations = Array.isArray(metadata.stations) ? metadata.stations : [];
            return {
              exitCode: 0,
              stdout: JSON.stringify(
                stations.filter((row) => object(row).projectId === agent.projectId),
              ),
            };
          },
        }),
      },
    }),
  );
}
