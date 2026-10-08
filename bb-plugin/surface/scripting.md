minecraft_run executes an async function body returning JSON. Globals: bot, Vec3, goals, Movements, Block, Item, Entity, ChatMessage, MessageBuilder, Recipe, RecipeItem, BossBar, ChunkColumn, Particle. No imports, filesystem, network or credentials. console.log is captured. Await actions. Deadline: 120s default/300s maximum; bounded memory, CPU and output.

Compatibility is incomplete. Typed observations cover entities/blocks/items/windows, searches, visibility/raycast, explosion estimates, players/game, scoreboards/teams/boss bars and body/world state. supportFeature describes the Minecraft version, not coverage. Recipes use pinned vanilla data; native craft preserves outputs/remainders.

Start directly: bot is initialized before entry; do not wait for spawn/login. No per-script player connection/respawn. Local API errors may emit `error` or reject; transport loss/invalid state aborts execution and is not recoverable through `error`.

Actions: look/lookAt, dig/canDigBlock/digTime/stopDigging, placeBlock, block/entity activation, equip/unequip, hotbar selection, inventory clicks/transfers/tossing, containers (including horse sneak-interaction), crafting, furnace/enchantment/anvil/villager windows, books and updateSign. Sign updates require first opening the native editor by interaction. Native clicks own slots/cursor/XP; closed Window handles expire. attack/swingArm, item activation/consume, placeEntity, mount/dismount and manual control states. Release manual inputs before Pathfinder navigation. Fishing/sleep/wake; elytraFly starts native gliding. moveVehicle(left, forward) controls boats, eligible saddled mounts and minecarts; native coast/steering rules apply. Creative slot edits preserve components; flight uses collision. setSettings supports only native mainHand (left/right). Chat/whisper, patterns and awaitMessage are available; commands require creative_commands mode. tabComplete queries native commands; setCommandBlock requires creative_commands and operator permission.

Pathfinder: goto, setGoal (including dynamic goals), setMovements, stop, isMoving/isMining/isBuilding, bestHarvestTool, getPathTo and synchronous getPathFromTo. Resume partial plans through the generator. Set policy via bot.pathfinder.setMovements(new Movements(bot)). Dry shortcuts/following require native preflight. Bottom slab at Y uses logical goal Y+1, physical feet Y+0.5.

Queries use a 33×17×33 local view and received 5×5 full columns, refreshed within five ticks and after actions. waitForChunksToLoad waits for complete columns without forcing loads. Missing blocks are null. world setters/async storage change only guest cache state, never Minecraft. Events include physicsTick, blockUpdate, entityHurt/entityDead/playerCollect, soundEffectHeard and inventory/state changes; full coverage remains pending. Stream loss fails the script without reconnecting/replaying.

Dig/place require reach; neither approaches. Routes enforce collision, tools, permissions and inventory costs. bot.nativeBody reports current capabilities, not guaranteed arrival. locomotion=submerged uses swimTargetYOffset and needs full body/eyes in ordinary water; locomotion=flying uses flightTargetYOffset and canFly; hopping retains native timing. Pass integral GoalBlock cells without offsets. Unsupported physics/state changes can terminate routes. Several modes remain unverified in-game. Ambient AI movement is suppressed; native physics continues.

Example:
const block = bot.findBlock({matching: bot.registry.blocksByName.oak_log.id});
if (!block) throw new Error('No loaded oak log');
await bot.pathfinder.goto(new goals.GoalNear(block.position.x, block.position.y, block.position.z, 2));
await bot.dig(block);
return bot.inventory.items().map(item => ({name:item.name,count:item.count}));

Reports include results, logs and action counts. Completed mutations remain after failure/cancellation. Never replay unknown outcomes; inspect first. Physical tools cannot take over a running script. minecraft_cancel stops its action and releases control.
