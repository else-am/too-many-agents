# Advanced observations

`findBlock(options)` returns the nearest found Block or null; `findBlocks` returns positions. Options: `point`, `matching` (type, type array or Block predicate), `maxDistance` (default 16), `count` (default 1), `useExtraInfo` (boolean or Block predicate). Searches cover observed cells only and reject more than 262144 candidate cells or 65536 requested results. Missing/unloaded cells do not count as matches. Rays have a 4096-block maximum and fail when entering unknown cells.

`blockAt(position, extraInfos=true)` includes observed block-entity data when requested. `blockAtCursor(maxDistance=256, matcher=null, entity=bot.entity)` preserves ordinary own-body calls and accepts an observed entity as the third argument. `entityAtCursor(maxDistance=3.5)` returns the nearest intersected observed entity subject to the loaded block ray. Block geometry (`shapes`, `boundingBox`, `canHarvest(type)`), entity velocity/eye position/metadata/attributes/equipment and Item component data are observations, not guarantees of native action success.

`bot.registry` retains name mappings for blocks/items/entities/biomes/attributes/effects/enchantments/foods, numeric item/effect/particle mappings, and block/entity loot planning hints. It is pinned game data; `bot.nativeRegistries` maps modern native component-holder IDs to world-specific names. Do not assume protocol IDs match those native IDs. `version` identifies the pinned game version, not Mineflayer coverage.

`players` maps names to observed player records (including entity when observed). `tablist.header`/`footer` are ChatMessages. `time` includes `timeOfDay`, `isDay`, `day`, `moonPhase`, `doDaylightCycle`, numeric `age` and exact bigint `bigAge`/`bigTime`; convert bigint to strings when returning JSON. `rainState` and `thunderState` are observed strengths. Dimension `game.minY`/`height` and `hardcore` remain available.

`scoreboards` maps objective names to boards with name/title and sorted `items` (`name`, `value`, computed ChatMessage `displayName`). `scoreboard` maps native numeric display slots to boards, with `list`, `sidebar`, `belowName` aliases. `teams` maps IDs to team name, members, friendlyFire, nameTagVisibility, collisionRule, color, prefix/suffix. `bossBars` contains UUID, title, health, color, dividers, shouldDarkenSky, isDragonBar and createFog. These are current snapshots; setters on local objects do not change Minecraft.

## Transient events

Use `bot.on(event, listener)`, `once` and `off`. The main message event is `(ChatMessage, position, senderUUIDOrNull, verified)`; position distinguishes chat/system/game_info. Transport loss terminates the script and is not a recoverable `error` event. Retained advanced events:

- `health`, `death`, `forcedMove`, `physicsTick`: interrupts/tick observations; inspect current body state. `physicsTick` follows received tick frames, not a local physics engine.
- `entitySpawn`, `entityGone`, `entityDead`, `entityHurt`, `entityEat`, `entityEatingGrass`, `entitySwingArm`, `entityCriticalEffect`, `entityMagicCriticalEffect`, `entityShakingOffWater`, `entityTaming`, `entityTamed`: native subject entity, with a cause entity when provided by the native observation. Event-only entities can disappear between snapshots.
- `playerCollect(collector, collected)`: capture dropped-item data in the callback; later observations may no longer contain it.
- `soundEffectHeard(name, position: Vec3, volume, pitch)`: `name` is the sound id without `minecraft:`; only sounds audible from the body are delivered.
- `particle(particle)`: `{ id, name, position: Vec3, offset: Vec3, count, movementSpeed, longDistanceRender }` (`name` is the registry name without `minecraft:`). Offsets are float-precision. Type-specific options (dust color, block/item data) are not delivered. Only particles within 32 blocks (512 if `longDistanceRender`) of the body and addressed to it arrive.
- `blockUpdate(before, after)`: Blocks for a cell whose state changed inside the observed block volume; reread neighbours yourself.
- `blockEntityData(block)`: only while a listener is registered; fires when an observed cell's block-entity data changed.
- `blockBreakProgressObserved(block, stage 0..9, breakerEntity|undefined)` and `blockBreakProgressEnd(block, breakerEntity|undefined)`: other entities' mining within 32 blocks; the body's own mining is excluded.
- `noteHeard(block, instrument {id, name}, note 0..24)`, `pistonMove(block, action, parameter)` (raw block-event bytes: action 0 extend, 1 retract, 2 cancel; parameter the facing 0 down, 1 up, 2 north, 3 south, 4 west, 5 east), `chestLidMove(block, openCount, partnerBlock|null)`: block actions within 64 blocks of loaded chunks. The `block` is the current observed block (a retracting piston base may already be gone; the event is skipped when the cell is unobserved). A double chest reports once, at its right half, with the left half as `partner`; repeated identical open counts are suppressed. Chests, ender chests and shulker boxes report lid movement.
- Entity events pass `(entity, causeEntity?)`: `entityHurt(victim, attacker)`, `playerCollect(collector, collected)`; the rest pass the subject only.
- More than 256 queued particle, sound or block events between two snapshots terminates the script, even with no listener. Avoid running scripts in extremely busy particle/sound areas.
- `title(text, type)`, `title_times(fadeIn, stay, fadeOut)`, `title_clear()`: transient presentation.

State/action-completion aliases are intentionally absent from the public subscription API. Internal dispatch still hydrates windows, resolves waits and completes native operations.

`addChatPattern(name, regex, options)` / `addChatPatternSet(name, patterns, options)` and `removeChatPattern(nameOrId)` support advanced matching; `chatPatterns` exposes local pattern state. Pattern matches emit `chat:<name>`. `awaitMessage` uses internal message text dispatch, even though that duplicate text event is not public. `ChatMessage`/`MessageBuilder` retain rich text parsing/building/formatting; use ordinary `.toString()` for normal text. Standard Vec3/text-library helpers need no separate compatibility wrapper.
