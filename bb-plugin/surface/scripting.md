Use minecraft_run for loops and awaited steps. `code` is an async function body returning JSON. Globals: bot, Vec3, goals, Movements, Block, Item, Entity, ChatMessage, MessageBuilder, Recipe, RecipeItem, BossBar, ChunkColumn, Particle. No imports, filesystem, network or credentials. console.log is captured. Await actions. Deadline: 120s default/300s maximum; bounded memory, CPU and output.

Compatibility is incomplete. Observations: typed entities/blocks/items/windows, searches, cursor/visibility/raycast, explosion estimates, players/game/username, scoreboards/teams/boss bars, time/health/air/rain. supportFeature describes the Minecraft version, not coverage. Recipes use pinned vanilla data; native craft preserves outputs/remainders.

The bot is initialized before script entry: start directly, without waiting for `spawn` or `login`. There is no per-script player connection/respawn. Local API errors may emit `error` or reject; transport loss/invalid state aborts execution and is not recoverable through `error`.

Implemented actions: look/lookAt, dig/canDigBlock/digTime/stopDigging, placeBlock, block/entity activation, equip/unequip, hotbar selection, inventory clicks/transfers/tossing, containers (including horse sneak-interaction), crafting, furnace/enchantment/anvil/villager windows, books and updateSign. Sign updates require first opening the native editor by interaction. Native clicks own slots/cursor/XP; Window handles expire on close. Also available: attack/swingArm, item activation/consume, placeEntity, mount/dismount and manual control states. Release manual inputs before Pathfinder navigation. Mob physics remain native. Fishing/sleep/wake; elytraFly starts native gliding. moveVehicle(left, forward) controls boats, eligible saddled mounts and minecarts; native coast/steering rules apply. Creative slot edits preserve components; flight uses collision. setSettings supports only native mainHand (left/right). Chat/whisper, patterns and awaitMessage are available; commands require creative_commands mode. tabComplete queries native commands; setCommandBlock requires creative_commands and operator permission.

Pathfinder: goto, setGoal (including dynamic goals), setMovements, stop, movement/mining/building queries, bestHarvestTool, getPathTo and synchronous getPathFromTo. Resume partial planning through its generator. Configure new Movements(bot) with bot.pathfinder.setMovements(movements). Dry shortcuts/following require native preflight; rejected candidates use ordinary planning.

Queries use a 33×17×33 local view and received 5×5 full columns, refreshed within five ticks and after actions. waitForChunksToLoad waits for complete columns without forcing loads. Missing blocks are null. world setters/async storage change only guest cache state, never Minecraft. on/once/removeListener support physicsTick, blockUpdate, nearby entity changes, entityHurt/entityDead/playerCollect, soundEffectHeard, inventory and state events; full event coverage remains pending. Stream loss fails the script without reconnecting/replaying.

Dig/place require existing reach; neither approaches for you. Routes enforce native collision, tools, permissions and inventory costs and may fail. Ground, water, ladder, slab, jump, dig and build checks passed; specialized species and broader cases remain pending. bot.nativeBody exposes current capabilities; jump bounds generate candidates, not guaranteed crossings. Ambient movement is suppressed; gravity continues.

Example:
const block = bot.findBlock({matching: bot.registry.blocksByName.oak_log.id});
if (!block) throw new Error('No loaded oak log');
await bot.pathfinder.goto(new goals.GoalNear(block.position.x, block.position.y, block.position.z, 2));
await bot.dig(block);
return bot.inventory.items().map(item => ({name:item.name,count:item.count}));

Reports include results, logs and action counts. Completed mutations remain after failure/cancellation. Never replay unknown outcomes; inspect first. Physical tools cannot take over a running script. minecraft_cancel stops its action and releases control.
