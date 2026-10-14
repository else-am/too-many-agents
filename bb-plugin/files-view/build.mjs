import { build } from "esbuild";
import { fileURLToPath } from "node:url";
const root = fileURLToPath(new URL("../../", import.meta.url));
await build({
  entryPoints: [fileURLToPath(new URL("./viewer.ts", import.meta.url))],
  outfile:
    root +
    "build/generated/files-view/assets/too_many_agents/builtin/viewer.js",
  bundle: true,
  format: "iife",
  platform: "browser",
  target: "chrome120",
  minify: true,
  legalComments: "eof",
  logLevel: "info",
});
