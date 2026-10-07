Use minecraft_run for procedures with loops and awaited steps. `code` is an async function body; use `return` for its JSON result. `bot`, `Vec3`, and Pathfinder `goals` are ready to use. No imports, filesystem, network, Node globals, or bridge credentials are exposed. console.log is captured. Await every physical operation. Default deadline is 120 seconds, maximum 300 seconds; output is bounded. Infinite synchronous execution is interrupted.

This is an initial, incomplete Mineflayer compatibility slice. Currently available: bot.entity.position, bot.registry.blocksByName/itemsByName, bot.inventory.items()/slots, bot.heldItem, bot.blockAt, bot.findBlock/findBlocks, bot.equip(itemOrType, destination), bot.dig(block), bot.waitForTicks, and bot.pathfinder.goto with GoalNear or GoalBlock. Other API members, dig options, findBlocks extra-info options, and the full Pathfinder planner are pending. Do not assume full upstream support yet.

Queries are synchronous and read a local view updated continuously while the script runs. It currently includes a 33×17×33 block volume centered on the body; blockAt returns null outside this view or for unloaded blocks. Blocks expose state properties, collision shapes, light, biome ID and client-visible block-entity data. Position and entity references remain stable across updates. `bot.on`/`once`/`removeListener` are available with initial `physicsTick`, `blockUpdate`, and nearby entity spawn/move/gone events; complete event conformance remains pending. A slow or interrupted state stream fails the script rather than silently reconnecting.

Mining requires existing reach and does not walk for you. Navigation currently uses native pathfinding and does not dig or place along its path. Script control suppresses ambient movement between steps; configured behavior resumes when control is released.

Example:
const block = bot.findBlock({ matching: bot.registry.blocksByName.oak_log.id });
if (!block) throw new Error('No loaded oak log in view');
await bot.pathfinder.goto(new goals.GoalNear(block.position.x, block.position.y, block.position.z, 2));
await bot.dig(block);
return bot.inventory.items().map(item => ({ name: item.name, count: item.count }));

The report includes the result, logs, bridge-operation count and recent action states. On failure or cancellation, completed mutations remain. A missing reply is an unknown outcome, not permission to repeat a mutation. Inspect before deciding what to do next. Direct physical tools remain available for unsupported operations, but another action or command cannot take over a running script. minecraft_cancel stops the script's current action and releases its body.
