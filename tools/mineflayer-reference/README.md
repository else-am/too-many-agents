# Mineflayer core reference

Pinned Mineflayer 4.39.0 / Minecraft 1.21.1 dependencies, core library comparisons and isolated reference-server scenarios. Pathfinder and navigation fixtures were removed from this branch; their source and historical results remain on `feat/mineflayer-api`.

Install dependencies with `npm ci` in this directory. Generate the core inventory with `npm run catalog`. This does not start Minecraft or BB. Coverage is evidence status, not an implementation percentage; a passing library comparison does not establish native gameplay.

`MINEFLAYER_REFERENCE_ROOT`, `MINEFLAYER_PLUGIN_ROOT` and `MINEFLAYER_SOURCE_ROOT` can select existing package/source installations. Generated reports live in ignored `run/mineflayer-reference/`; recorded hashes refer to the exact historical sources and results. Changed harnesses require new evidence, never replacement hashes for old runs.

The loopback-only reference server uses its own disposable world. `node scenarios.mjs` runs the missing-materials crafting case by default. Focused options include `--world-queries`, `--container`, `--equipment-common`, `--placement-refusal`, `--dig-unsuitable-tool`, `--dig-cancel`, `--dig-disappearing`, `--wall-convergence`, and `--building` (wall placement). Reference runs are distinct from the mod's native checks. Follow AGENTS.md for native client setup, isolation and lifecycle.

The user accepted the reference server EULA earlier. Do not manage the user's BB installation or personal Minecraft world. A reference pass is not a native pass; failed historical comparisons are not silently promoted.
