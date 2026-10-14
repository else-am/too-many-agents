#!/usr/bin/env node
// Turns an extracted copy of the packaged plugin into one development client's
// own plugin instance, in place:
//   node stage-development.mjs <dir> <pluginId> <clientId>
// Self-contained: the launcher runs a frozen copy of this file.
import { createHash } from "node:crypto";
import { realpathSync } from "node:fs";
import { readdir, readFile, rename, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

// The packaged `minecraft` plugin keeps canonical names. Other instances get
// their own tool, CLI and skill names, because BB keeps these unique across plugins.
export function namespace(pluginId, toolNames) {
  const canonical = pluginId === "minecraft";
  const prefix = pluginId.replaceAll("-", "_");
  const tool = (name) => canonical ? name : `${prefix}_${name.replace(/^minecraft_/, "")}`;
  const tools = new RegExp(`\\b(${toolNames.join("|")})\\b`, "g");
  // Already rewritten text is left unchanged.
  const rewrite = (text) => canonical ? text : text
    .replace(/\bbb minecraft(?![\w-])/g, `bb ${pluginId}`)
    .replace(/\bminecraft-(agents|scripting)\b/g, (_, skill) => `${pluginId}-${skill}`)
    .replace(tools, tool);
  return { cli: pluginId, tool, skill: rewrite, rewrite };
}

async function files(root, directory = "") {
  const found = [];
  for (const entry of await readdir(join(root, directory), { withFileTypes: true })) {
    const path = directory ? `${directory}/${entry.name}` : entry.name;
    if (entry.isDirectory()) found.push(...await files(root, path));
    else if (entry.isFile()) found.push(path);
    else throw new Error(`Unexpected file type in the plugin bundle: ${path}`);
  }
  return found.sort();
}

async function stage(dir, pluginId, client) {
  // BB derives the plugin id from the package name; keep it in that form.
  if (!/^minecraft(-[a-z0-9]+)+$/.test(pluginId ?? ""))
    throw new Error("pluginId must look like minecraft-dev-a.");
  if (!/^[a-z0-9]+(-[a-z0-9]+)*$/.test(client ?? "") || client.length > 32)
    throw new Error("clientId must be lowercase letters, digits and single hyphens.");
  const manifest = JSON.parse(await readFile(join(dir, "package.json"), "utf8"));
  if (manifest.name !== "bb-plugin-minecraft")
    throw new Error("Stage a fresh extracted copy of the packaged Minecraft plugin.");
  const tools = JSON.parse(await readFile(join(dir, "surface/tools.json"), "utf8"));
  const names = namespace(pluginId, tools.map((tool) => tool.name));
  const long = tools.map((tool) => names.tool(tool.name)).find((name) => name.length > 64);
  if (long) throw new Error(`pluginId is too long: providers limit tool names such as ${long} to 64 characters.`);
  // BB refuses tool instructions over 4096 UTF-16 units and truncates dynamic ones.
  for (const file of ["surface/scripting.md", "surface/agents.md"])
    if (names.rewrite(await readFile(join(dir, file), "utf8")).length > 4096)
      throw new Error(`pluginId is too long: ${file} would exceed BB's 4096-character instruction limit.`);

  manifest.name = `bb-plugin-${pluginId}`;
  manifest.bb.name = `Minecraft (${client})`;
  await writeFile(join(dir, "package.json"), JSON.stringify(manifest, null, 2) + "\n");
  // Skills are static files BB reads itself; the server rewrites its other texts when it loads.
  for (const path of await files(dir, "skills"))
    if (path.endsWith(".md")) await writeFile(join(dir, path), names.rewrite(await readFile(join(dir, path), "utf8")));
  for (const skill of ["minecraft-agents", "minecraft-scripting"])
    await rename(join(dir, "skills", skill), join(dir, "skills", names.skill(skill)));

  const hash = createHash("sha256");
  for (const path of await files(dir)) {
    if (path === "development.json") continue;
    hash.update(`${path}\0${createHash("sha256").update(await readFile(join(dir, path))).digest("hex")}\n`);
  }
  const development = { protocol: 1, pluginId, client, fingerprint: hash.digest("hex") };
  await writeFile(join(dir, "development.json"), JSON.stringify(development) + "\n");
  return development;
}

// Run only as a command; the plugin server imports namespace() from this file.
function invokedDirectly() {
  try { return import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href; }
  catch { return false; }
}
if (invokedDirectly()) {
  // No top-level await: BB bundles this file into its plugin server.
  const [dir, pluginId, client] = process.argv.slice(2);
  (dir ? stage(dir, pluginId, client) : Promise.reject(new Error("usage: node stage-development.mjs <dir> <pluginId> <clientId>")))
    .then((development) => console.log(JSON.stringify(development)))
    .catch((error) => {
      console.error(error instanceof Error ? error.message : String(error));
      process.exitCode = 1;
    });
}
