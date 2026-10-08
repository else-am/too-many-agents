# Remaining runtime surface (before implementation)

Plugin helpers follow Mineflayer 4.39.0 plugin_loader.js after injection is allowed:
loadPlugin/loadPlugins/hasPlugin accept script-local functions, preserve identity,
register before synchronous invocation, invoke once with this bot and shared
options, and return void. Validate the entire array before invoking any member.
Duplicate/reentrant loads do not invoke twice; thrown initializers remain marked
loaded as upstream does. The guest still has no import/Node/host loader or new
third-party package guarantee. Public protocolVersion and majorVersion come from
the pinned registry used to build this guest.

WorldSync numeric getters follow prismarine-world 3.7.0: getBlockStateId,
getBlockType, getBlockData, getBlockLight, getSkyLight and getBiome. Use the actual
observed Block/state/light/biome. Unknown cells return null explicitly rather than
upstream's ambiguous zero (which is also known air/darkness/biome 0). WorldSync
is an EventEmitter; native block updates reach ordinary and position-specific
world listeners after cache and complete bot state hydration, also reaching bot
listeners. Do not fabricate column load events from partially observed columns.

Focused combined checks: plugin identity/duplicate/bad-array behavior plus a
loaded slab's six getter results; unknown cell returns null. A native block edit
must reach both bot and world listeners, with the new block readable during each
callback. Register only a world listener too: it must still receive updates.
SpawnPoint reads the native level's shared spawn position and changes with it,
using a Vec3 and the existing game notification. No body respawn is invented.

World columns, async storage/mutation APIs, settings and remaining lifecycle
members stay pending. These additions do not establish complete world support.

## Recorded script-local plugin comparison

`plugin-helpers.mjs` executes the actual production bot bundle in QuickJS (64MiB memory,512KiB stack) against pinned plugin_loader after inject_allowed. The preauthored plugin criteria pass, including nine invalid-input cases, reentry and thrown initializer retention. Initialization uses an explicit synthetic empty observation; the first setup attempt encoded empty Slot as null and stopped before the scenario. Correcting it to itemCount0 allowed initialization without product changes. Native requests are forbidden by the harness. Invalid arguments produce TypeError here versus upstream Node AssertionError; both reject synchronously before registration. This check does not cover world getters, block events, native actions inside plugins or arbitrary external packages.
