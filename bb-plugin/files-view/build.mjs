import { build } from "esbuild";
import { fileURLToPath } from "node:url";
const root = fileURLToPath(new URL("../../", import.meta.url));
await build({
  // The picker is its own small bundle, so it never loads syntax highlighting.
  entryPoints: ["viewer", "picker"].map((name) => fileURLToPath(new URL(`./${name}.ts`, import.meta.url))),
  outdir: root + "build/generated/files-view/assets/too_many_agents/builtin",
  bundle: true,
  format: "iife",
  platform: "browser",
  target: "chrome120",
  minify: true,
  legalComments: "eof",
  logLevel: "info",
});
