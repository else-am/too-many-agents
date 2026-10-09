import { readFile } from "node:fs/promises";
import type { BbPluginApi, PluginAgentToolResult } from "@get-bb/plugin-sdk";
import { communicationNotices } from "./communication.js";
import { registerGameApi } from "./game-api.js";
import { registerMinecraftCli } from "./cli.js";
import { minecraftWorlds } from "./minecraft.js";
import { minecraftThreads } from "./threads.js";
import { minecraftAgents } from "./agents.js";
import { minecraftProjects } from "./projects.js";
import { describe } from "./protocol.js";
import { savePovSnapshot } from "./images.js";
import { publishDiscovery } from "./discovery.js";
import { minecraftScripts } from "./scripts.js";

export default async function minecraft(bb: BbPluginApi) {
  const worlds = minecraftWorlds(bb);
  const scripts = minecraftScripts(bb, worlds);
  const threads = minecraftThreads(bb, worlds);
  const projects = minecraftProjects(bb, worlds);
  const agents = minecraftAgents(bb, worlds, threads, projects);
  const setup = publishDiscovery(bb, worlds);
  registerGameApi(bb, worlds, threads, agents, projects, setup.check);
  communicationNotices(bb, worlds);
  registerMinecraftCli(bb, worlds, agents);

  const tools = JSON.parse(
    await readFile(new URL("./surface/tools.json", import.meta.url), "utf8"),
  ) as Array<{
    name: string;
    description: string;
    minecraft?: boolean;
    inputSchema: Record<string, unknown>;
  }>;
  const physicalInstructions = await readFile(
    new URL("./surface/minecraft.md", import.meta.url),
    "utf8",
  );
  const instructions = await readFile(new URL("./surface/agents.md", import.meta.url), "utf8");
  const scriptingInstructions = await readFile(new URL("./surface/scripting.md", import.meta.url), "utf8");
  const physicalTools = tools.filter((tool) => tool.minecraft);

  for (const tool of physicalTools) {
    bb.agents.registerTool({
      name: tool.name,
      description: tool.description,
      parameters: tool.inputSchema,
      ...(tool.name === "minecraft_observe"
        ? { instructions: physicalInstructions.slice(0, 4096) }
        : tool.name === "minecraft_run" ? { instructions: scriptingInstructions } : {}),
      async execute(args, ctx): Promise<PluginAgentToolResult> {
        try {
          const { live, agent } = await worlds.caller(ctx.threadId);
          if (tool.name === "minecraft_run") return await scripts(args, ctx, live, agent.agentId);
          const result = (await worlds.toolCallback(live, ctx, "tool", {
            agentId: agent.agentId,
            tool: tool.name,
            arguments: args,
          })) as PluginAgentToolResult;
          return tool.name === "minecraft_pov" ? await savePovSnapshot(bb, ctx, result) : result;
        } catch (error) {
          return {
            content: [
              {
                type: "text",
                text: `${describe(error)}. No automatic retry was attempted.`,
              },
            ],
            isError: true,
          };
        }
      },
    });
  }

  bb.agents.configure(({ pluginMetadata }) => {
    if (typeof pluginMetadata.worldId !== "string" || typeof pluginMetadata.agentId !== "string")
      return { tools: [], skills: [] };
    return {
      tools: physicalTools
        .filter((tool) => !tool.minecraft || pluginMetadata.minecraftAccess === true)
        .map((tool) => tool.name),
      skills: ["minecraft-agents", ...(pluginMetadata.minecraftAccess === true ? ["minecraft-scripting"] : [])],
      instructions,
    };
  });
}
