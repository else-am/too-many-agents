import { mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
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

export type Development = { client: string; fingerprint: string } | null;

/** A development client's plugin is a staged copy with development.json; the packaged `tma` plugin has none. */
export async function loadDevelopment(bb: BbPluginApi): Promise<Development> {
  let data;
  try {
    data = JSON.parse(await readFile(new URL("./development.json", import.meta.url), "utf8"));
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    if (bb.pluginId !== "tma") throw new Error("Install development plugins through tools/dev; this copy was not staged.");
    return null;
  }
  if (data.pluginId !== bb.pluginId || typeof data.client !== "string" || typeof data.fingerprint !== "string")
    throw new Error("development.json was staged for another plugin.");
  return { client: data.client, fingerprint: data.fingerprint };
}

/** A mod-owned rendezvous, independent of BB's installation and data directories. */
export function publishDiscovery(bb: BbPluginApi, worlds: MinecraftWorlds, development: Development) {
  // This identity survives restarts and plugin replacement. BB provides its
  // configured directory; we never inspect or write its internal files.
  const instanceId = bbInstanceId(bb);
  let updatingUntil = 0;
  let preparing = false;
  const generation = randomUUID();
  // Every plugin instance in one BB shares instanceId; games choose by pluginId.
  const identity = () => ({ instanceId, generation, label: bb.server.experimental_dataDir, pluginId: bb.pluginId,
    pluginVersion: MOD_VERSION, development, serverUrl: bb.server.loopbackBaseUrl });
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
  bb.background.service("tma-discovery", {
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
        throw new ApiError("version_mismatch", `Too Many Agents mod ${String(data.modVersion ?? "unknown")} and plugin ${MOD_VERSION} must match. Open BB connection in mod settings.`);
      // A development game names the exact staged bundle it was launched against.
      if ((data.developmentFingerprint ?? null) !== (development?.fingerprint ?? null))
        throw new ApiError("plugin_changed", "This Too Many Agents plugin is not the one this game was set up for. Open BB connection in mod settings.");
      if ((preparing || updatingUntil > Date.now()) && data.op !== "session.detach")
        throw new ApiError("plugin_updating", "The Too Many Agents plugin is being updated. Retry shortly.");
    },
  };
}
