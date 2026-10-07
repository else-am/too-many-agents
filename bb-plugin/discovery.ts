import { mkdir, rename, rm, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { randomUUID, createHash } from "node:crypto";
import { setTimeout } from "node:timers/promises";
import type { BbPluginApi } from "@get-bb/plugin-sdk";
import type { MinecraftWorlds } from "./minecraft.js";
import { ApiError, describe, type ObjectValue } from "./protocol.js";
import manifest from "./package.json" with { type: "json" };

export const MOD_VERSION = manifest.version;
export function bbInstanceId(bb: BbPluginApi) {
  return createHash("sha256").update(bb.server.experimental_dataDir).digest("hex");
}

/** A mod-owned rendezvous, independent of BB's installation and data directories. */
export function publishDiscovery(bb: BbPluginApi, worlds: MinecraftWorlds) {
  // This identity survives restarts and plugin replacement. BB provides its
  // configured directory; we never inspect or write its internal files.
  const instanceId = bbInstanceId(bb);
  let updatingUntil = 0;
  let preparing = false;
  const generation = randomUUID();
  const identity = () => ({ instanceId, generation, label: bb.server.experimental_dataDir, pluginVersion: MOD_VERSION, serverUrl: bb.server.loopbackBaseUrl });
  bb.http.route("GET", "/v1/setup", async ctx => {
    const plugin = (await bb.sdk.plugins.list()).plugins.find(plugin => plugin.id === bb.pluginId);
    return ctx.json({ ...identity(), pluginRoot: plugin?.rootDir, games: await worlds.activeGames() });
  });
  bb.http.route("POST", "/v1/setup/prepare", async ctx => {
    try {
      if (preparing || updatingUntil > Date.now()) throw new Error("Another Minecraft installation is updating this plugin. Retry shortly.");
      // Close the attach race before checking sessions, including asynchronous probes.
      preparing = true;
      try {
        if ((await worlds.activeGames(true)).length) throw new Error("Another Minecraft game is connected to this BB. Close that game before replacing the plugin.");
        updatingUntil = Date.now() + 120_000;
      } finally { preparing = false; }
      return ctx.json({ ok: true, ...identity() });
    } catch (error) { return ctx.json({ ok: false, error: describe(error) }, 409); }
  });
  bb.background.service("minecraft-discovery", {
    async start(signal) {
      const directory = join(homedir(), ".too-many-agents", "bb");
      const file = join(directory, `${randomUUID()}.json`);
      const temporary = `${file}.tmp`;
      await mkdir(directory, { recursive: true, mode: 0o700 });
      try {
        while (!signal.aborted) {
          await writeFile(temporary, JSON.stringify({
            protocol: 1,
            ...identity(),
            expiresAt: Date.now() + 15_000,
          }), { mode: 0o600 });
          await rename(temporary, file);
          await setTimeout(3_000, undefined, { signal });
        }
      } catch (error) {
        if (!signal.aborted) throw error;
      } finally {
        await rm(temporary, { force: true });
        await rm(file, { force: true });
      }
    },
  });
  return {
    check(data: ObjectValue) {
      if (data.op === "hello") return;
      if (data.bbInstanceId !== instanceId)
        throw new ApiError("bb_instance_changed", "This request belongs to a different BB instance.");
      if (data.modVersion !== MOD_VERSION)
        throw new ApiError("version_mismatch", `Minecraft mod ${String(data.modVersion ?? "unknown")} and plugin ${MOD_VERSION} must match. Open BB connection in mod settings.`);
      if ((preparing || updatingUntil > Date.now()) && data.op !== "session.detach")
        throw new ApiError("plugin_updating", "The Minecraft plugin is being updated. Retry shortly.");
    },
  };
}
