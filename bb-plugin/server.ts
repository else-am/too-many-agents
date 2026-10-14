import { readFile } from "node:fs/promises";
import type { BbPluginApi, PluginAgentToolContext, PluginAgentToolResult } from "@get-bb/plugin-sdk";
import { communicationNotices } from "./communication.js";
import { registerGameApi } from "./game-api.js";
import { registerTmaCli } from "./cli.js";
import { minecraftWorlds } from "./minecraft.js";
import { minecraftThreads } from "./threads.js";
import { minecraftAgents } from "./agents.js";
import { minecraftProjects } from "./projects.js";
import { describe } from "./protocol.js";
import { savePovSnapshot } from "./images.js";
import { loadDevelopment, publishDiscovery } from "./discovery.js";
import { namespace } from "./stage-development.mjs";
import { minecraftScripts } from "./scripts.js";
import { screenTools } from "./screens.js";

export default async function tma(bb: BbPluginApi) {
  const worlds = minecraftWorlds(bb);
  const scripts = minecraftScripts(bb, worlds);
  const screen = screenTools(bb, worlds);
  const threads = minecraftThreads(bb, worlds);
  const projects = minecraftProjects(bb, worlds);
  const agents = minecraftAgents(bb, worlds, threads, projects);
  const development = await loadDevelopment(bb);
  const setup = publishDiscovery(bb, worlds, development);
  registerGameApi(bb, worlds, threads, agents, projects, setup.check, development);
  communicationNotices(bb, worlds);

  const tools = JSON.parse(
    await readFile(new URL("./surface/tools.json", import.meta.url), "utf8"),
  ) as Array<{
    name: string;
    description: string;
    minecraft?: boolean;
    agent?: boolean;
    inputSchema: Record<string, unknown>;
  }>;
  // Java always receives canonical tool names; agents see this instance's names.
  const names = namespace(bb.pluginId, tools.map((tool) => tool.name));
  registerTmaCli(bb, worlds, agents, names);
  const instructions = names.rewrite(await readFile(new URL("./surface/agents.md", import.meta.url), "utf8"));
  const scriptingInstructions = names.rewrite(await readFile(new URL("./surface/scripting.md", import.meta.url), "utf8"));
  // Tools marked agent: false stay available to development diagnostics only.
  const agentTools = tools.filter((tool) => tool.agent !== false);

  // Java's error hints use canonical tool names. Successful results are world content; leave them as they are.
  const named = (result: PluginAgentToolResult): PluginAgentToolResult =>
    typeof result === "string" || !result.isError ? result : {
      ...result,
      content: result.content.map((part) => part.type === "text" ? { ...part, text: names.rewrite(part.text) } : part),
    };

  for (const tool of agentTools) {
    async function run(args: unknown, ctx: PluginAgentToolContext): Promise<PluginAgentToolResult> {
      if (tool.name.startsWith("screen_")) return screen(tool.name, args, ctx);
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
    }
    bb.agents.registerTool({
      name: names.tool(tool.name),
      description: names.rewrite(tool.description),
      parameters: tool.inputSchema,
      ...(tool.name === "minecraft_run" ? { instructions: scriptingInstructions } : {}),
      async execute(args, ctx) {
        return named(await run(args, ctx));
      },
    });
  }

  bb.agents.configure(({ pluginMetadata, project }) => {
    if (typeof pluginMetadata.worldId !== "string" || typeof pluginMetadata.agentId !== "string")
      return { tools: [], skills: [] };
    // Only agents in their world's own project act in Minecraft. Java checks every call again.
    const access = project.id === worlds.worldProjects.get(pluginMetadata.worldId);
    return {
      tools: agentTools.filter((tool) => !tool.minecraft || access).map((tool) => names.tool(tool.name)),
      skills: ["tma-agents", ...(access ? ["tma-scripting"] : [])].map(names.skill),
      instructions: access ? `${instructions}

Physical tools control only your own Minecraft body. Use registered tools for game actions; do not bypass them using shell or HTTP. Access follows the project, and children cannot gain Creative/cheat abilities beyond the caller. Never retry an uncertain physical mutation. World departure makes physical tools unavailable; BB conversation changes never undo world mutations.` : instructions,
    };
  });
}
