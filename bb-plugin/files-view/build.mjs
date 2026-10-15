import { build } from "esbuild";
import { fileURLToPath } from "node:url";
import { rm } from "node:fs/promises";
const root = fileURLToPath(new URL("../../", import.meta.url));
const outdir = root + "build/generated/files-view/assets/too_many_agents/builtin";
// Drop outputs from removed entry points as well as rebuilding the viewer.
await rm(outdir, { recursive: true, force: true });
await build({
  entryPoints: [fileURLToPath(new URL("./viewer.ts", import.meta.url))],
  outdir,
  bundle: true,
  format: "iife",
  platform: "browser",
  target: "chrome120",
  minify: true,
  legalComments: "eof",
  logLevel: "info",
});
