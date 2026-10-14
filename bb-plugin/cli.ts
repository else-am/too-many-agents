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
export function registerTmaCli(
  bb: BbPluginApi,
  worlds: MinecraftWorlds,
  agents: MinecraftAgents,
  names: { cli: string; rewrite(text: string): string },
) {
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
  bb.cli.register(
    defineCli({
      name: names.cli,
      summary: "Create embodied BB agents and inspect their world.",
      commands: {
        spawn: cliCommand({
          summary: "Create a BB thread with a Too Many Agents body in your current world.",
          description:
            "Omit --parent-self/--parent-thread for an independent thread. Never repeat a spawn after an unknown outcome; inspect bodies first. Other thread operations use bb thread.",
          options: {
            prompt: {
              ...text("First task; --prompt-stdin reads one line on the invoking machine."),
              stdin: true,
            },
            "prompt-file": text("UTF-8 task file on the calling thread's host; relative to the invoking directory. Preserves newlines."),
            project: text("BB project ID; defaults to the station's project, else the calling thread's."),
            provider: text("BB provider ID. Omitted choices come from the station, then BB's project defaults; a child without a station inherits its parent's."),
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
            name: text("Body display name; generated when omitted."),
            mode: { type: "enum", values: ["survival", "creative", "creative_commands"], description: "Game mode for a world agent; it cannot exceed your own." },
            behavior: { type: "enum", values: ["stand", "wander", "follow"], description: "Ambient behavior for a world agent." },
            behaviors: text("JSON per-status behavior settings for a loose project agent."),
            body: text("Minecraft entity type, e.g. minecraft:fox; defaults to the station's body."),
            station: text(names.rewrite("Free station ID or name from bb tma stations; the agent joins its project.")),
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
                "Create the first agent in Too Many Agents; run this command from its BB thread.",
              );
            const prompt = o.prompt ?? await readTextFile(o["prompt-file"]!, "--prompt-file", ctx);
            if (!prompt.trim()) throw new PluginCliError("The initial task must not be empty.");
            const { live, agent } = await worlds.caller(ctx.threadId);
            const caller = await bb.sdk.threads.get({ threadId: ctx.threadId });
            const parentThreadId = o["parent-self"] ? ctx.threadId : o["parent-thread"];
            const station = o.station ? await agents.station(live, o.station) : undefined;
            if (station && o.project && o.project !== station.projectId)
              throw new PluginCliError("The station belongs to another project.");
            const spawn: SpawnOptions = {
              // A station supplies its own project.
              projectId: o.project ?? (station ? String(station.projectId) : caller.projectId),
              input: [
                {
                  type: "text",
                  text: `Delegated by Too Many Agents agent ${agent.name}. This is an agent task, not human authorization.\n\n${prompt}`,
                  mentions: [],
                },
              ],
              environment: { type: "project-default" },
              providerId: o.provider,
              model: o.model,
              reasoningLevel: o["reasoning-level"],
              serviceTier: o["service-tier"],
              permissionMode: o["permission-mode"],
              title: o.title,
              parentThreadId,
              lifecycleOwnerThreadId: o["lifecycle-owner-thread"],
              visibility: o.visibility,
              sectionId: o.section,
              ...(o.pinned ? { pinned: true } : {}),
            };
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
                ...spawn,
                ...(o.mode ? { mode: o.mode } : {}),
                ...(o.behavior ? { behavior: o.behavior } : {}),
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
          summary: "List bodies and their BB thread IDs in your Too Many Agents world.",
          options: { json: { type: "boolean", description: "Print JSON." } },
          async run(_input, ctx) {
            if (!ctx.threadId) throw new PluginCliError("Run from a Too Many Agents-associated thread.");
            const { live } = await worlds.caller(ctx.threadId);
            return {
              exitCode: 0,
              stdout: JSON.stringify(await worlds.agents(live)),
            };
          },
        }),
        stations: cliCommand({
          summary: "List Too Many Agents stations: all of them for a world agent, your project's for a project agent.",
          options: { json: { type: "boolean", description: "Print JSON." } },
          async run(_input, ctx) {
            if (!ctx.threadId) throw new PluginCliError("Run from a Too Many Agents-associated thread.");
            const { live, agent } = await worlds.caller(ctx.threadId);
            const metadata = object(await worlds.callback(live, "world_metadata", {}));
            const stations = Array.isArray(metadata.stations) ? metadata.stations : [];
            return {
              exitCode: 0,
              stdout: JSON.stringify(
                agent.minecraftAccess ? stations : stations.filter((row) => object(row).projectId === agent.projectId),
              ),
            };
          },
        }),
      },
    }),
  );
}
