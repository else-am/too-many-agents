# Mineflayer reference checks

This is a separate vanilla Minecraft 1.21.1 server, used to compare behavior
with the mod's native bodies. It binds to `127.0.0.1:25575`, uses offline bot
authentication, and stores its disposable world under `run/mineflayer-reference`.
It never opens the user's game to LAN or manages BB.

Use Node 22 or newer and Java 21:

```sh
npm ci --prefix tools/mineflayer-reference --ignore-scripts
npm --prefix tools/mineflayer-reference run prepare
```

Preparation downloads the official server and checks its SHA-1. Before starting
it, the user must accept [Minecraft's EULA](https://www.minecraft.net/en-us/eula)
and set `eula=true` in `run/mineflayer-reference/eula.txt`. Preparation leaves
this false and preserves an existing choice.

```sh
npm --prefix tools/mineflayer-reference run check
```

The check starts its own server, prepares a deterministic fixture, runs
`gather.js` against the pinned Mineflayer installation, checks the block and
inventory on the server, writes `gather-reference.json` in the ignored run
directory, and stops its server. Do not run it against an already-running
reference server. `gather.js` is also the procedure for the native-body check.
At present this is one initial scenario, not full conformance coverage.

`npm --prefix tools/mineflayer-reference run catalog` generates the declaration
and documentation inventory in the run directory. `coverage.json` associates
coverage decisions with executable evidence; inventory counts are not proof
of compatibility. Pins and exact revisions live in `upstream.json` and the
package lockfile.

The independent `node tools/script-runtime-check.mjs` check exercises the real
QuickJS worker boundary: awaited operations, isolation, execution limits,
cancellation and bounded output. It does not claim gameplay conformance.
