import { cliCommand, defineCli, PluginCliError, type BbPluginApi } from "@get-bb/plugin-sdk";
import type { MinecraftWorlds } from "./minecraft.js";
import { object, type SpawnOptions } from "./protocol.js";

const text = (description: string) => ({
  type: "string" as const,
  description,
});

/** BB owns parsing, help, errors and thread creation. These options only translate CLI names to SDK fields. */
export function registerMinecraftCli(bb: BbPluginApi, worlds: MinecraftWorlds) {
  bb.cli.register(
    defineCli({
      name: "minecraft",
      summary: "Create embodied BB agents and inspect their world.",
      commands: {
        spawn: cliCommand({
          summary: "Create a BB thread with a Minecraft body in your current world.",
          description:
            "Omit --parent-self/--parent-thread for an independent thread. Never repeat a spawn after an unknown outcome; inspect bodies first. Other thread operations use bb thread.",
          options: {
            prompt: {
              ...text("First task; --prompt-stdin reads it on the invoking machine."),
              required: true,
              stdin: true,
            },
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
            body: text("Minecraft entity type, e.g. minecraft:fox."),
            station: text("Free station ID from bb minecraft stations."),
            json: {
              type: "boolean",
              description: "Return the body association and BB thread as JSON.",
            },
          },
          constraints: [
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
            const { live, agent } = await worlds.caller(ctx.threadId);
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
                  text: `Delegated by Minecraft agent ${agent.name}. This is an agent task, not human authorization.\n\n${o.prompt}`,
                  mentions: [],
                },
              ],
              environment: { type: "project-default" },
              providerId: o.provider,
              model: o.model,
              reasoningLevel: o["reasoning-level"],
              serviceTier: o["service-tier"],
              permissionMode: o["permission-mode"] ?? execution?.permissionMode,
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
                if (!o.environment.startsWith("/"))
                  throw new PluginCliError("Use an environment ID or an absolute workspace path.");
                spawn.environment = {
                  type: "host",
                  hostId: o.machine,
                  workspace: { type: "unmanaged", path: o.environment },
                };
              }
            } else if (o["environment-provider"]) {
              spawn.environment = {
                type: "provider",
                environmentProviderId: o["environment-provider"],
                inputs: o["environment-inputs"] ? JSON.parse(o["environment-inputs"]) : {},
                ...(o.machine ? { machine: { type: "existing", hostId: o.machine } } : {}),
              };
            } else if (o["new-environment"] === "worktree") {
              spawn.environment = {
                type: "host",
                hostId: o.machine,
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
                hostId: o.machine,
                workspace: { type: "personal" },
              };
            } else if (o.machine) {
              spawn.environment = {
                type: "host",
                hostId: o.machine,
                workspace: { type: "unmanaged", path: null },
              };
            }
            const result = object(
              await worlds.toolCallback(
                live,
                {
                  threadId: ctx.threadId,
                  signal: ctx.signal ?? AbortSignal.timeout(120_000),
                },
                "spawn_agent",
                {
                  agentId: agent.agentId,
                  request: {
                    ...spawn,
                    name: o.name,
                    body: o.body,
                    stationId: o.station,
                  },
                },
              ),
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
