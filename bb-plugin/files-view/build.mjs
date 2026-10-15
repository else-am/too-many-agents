import { build } from "esbuild";
import { fileURLToPath } from "node:url";
import { copyFile, rm } from "node:fs/promises";
const root = fileURLToPath(new URL("../../", import.meta.url));
const outdir = root + "build/generated/files-view/assets/too_many_agents/builtin";
// Drop outputs from removed entry points as well as rebuilding the viewer.
await rm(outdir, { recursive: true, force: true });
await build({
  entryPoints: ["./viewer.ts", "../terminal-view/terminal.ts"].map(path => fileURLToPath(new URL(path, import.meta.url))),
  external: ["/_app/pixel-code.ttf"],
  outdir,
  entryNames: "[name]",
  bundle: true,
  format: "iife",
  platform: "browser",
  target: "chrome120",
  minify: true,
  legalComments: "eof",
  logLevel: "info",
});
for (const name of ["xterm", "addon-fit"])
  await copyFile(new URL(`../node_modules/@xterm/${name}/LICENSE`, import.meta.url), `${outdir}/${name}-LICENSE.txt`);
