---
name: minecraft-scripting
description: "Control an attached Minecraft NPC with minecraft_run: observe blocks, entities and inventory, move with native navigation, and perform native gameplay actions."
---

Use `minecraft_run` with an async JavaScript function body. `bot` is already initialized; return JSON and use `console.log` for brief diagnostics. Globals are `bot`, `Vec3`, `Item`, `ChatMessage` and `MessageBuilder`. There are no imports, filesystem or network. This is a deliberate Mineflayer subset for a singleplayer native NPC, not a player connection or complete Mineflayer compatibility.

Observe → act → inspect. Read `bot.entity` (position, health, pose, velocity, equipment, effects, passengers/vehicle), `entities`, `heldItem`, `inventory.items()`, `currentWindow`, `experience.level`, `game` (`dimension`, `gameMode` 'survival'|'creative', `difficulty`), `time.timeOfDay`/`isDay`, `isRaining`, `spawnPoint`, `username` and `usingHeldItem`. Observed objects update during awaits; copy values such as `position.clone()` when you need a before-state.

```js
const start = bot.entity.position.clone();
await bot.moveTo(start.offset(3, 0, 0));
return { position: bot.entity.position, inventory: bot.inventory.items().map(i => ({ name: i.name, count: i.count })) };
```

`await bot.moveTo(position)` uses this body's native navigation in loaded, simulated chunks and its permitted box. Arrival means the requested block cell within 0.9 blocks of the position. Targets must be within 64 blocks and a move is limited to 60 seconds of game time. Unreachable paths, timeout, confinement (`detail: 'confined_to_box'`) and other failures reject with `code: 'MovementFailed'` plus `status`, `detail` and the final `position`; starting a second move before the first settles rejects with `MovementAlreadyRunning`. Choose a standable destination; navigation does not dig, build, teleport or load chunks. Dismount, wake and end creative flight/gliding before ground navigation. Different bodies have different native navigators. These movement error codes cover accepted movement actions; starting while another native action runs can instead reject with `action_already_running_cancel_or_wait`.

`await bot.stopMoving()` stops this script's navigation/creative translation and clears movement inputs; repeating it is harmless. An interrupted move rejects with `code: 'MovementStopped'`; attach a rejection handler when starting movement you intend to interrupt. It leaves creative flight enabled and does not cancel mining, inventory or item use. Script termination releases flight and controls.

```js
const moving = bot.moveTo(bot.entity.position.offset(12, 0, 0))
  .catch(error => { if (error.code !== 'MovementStopped') throw error; });
await bot.waitForTicks(5);
await bot.stopMoving();
await moving;
```

Find blocks using `bot.findBlocks({ matching: bot.registry.blocksByName.oak_log.id, maxDistance: 16, count: 8 })`; results are positions. `bot.blockAt(position)` returns a Block or null for unavailable data. Inspect name/type/stateId, position, hardness/diggable, light, biome, `getProperties()` and `blockEntity`. `bot.blockAtCursor(maxDistance?, matcher?, entity?)` raycasts from your body by default; the optional third argument is an observed entity. `canSeeBlock(block)` tests observed visibility. `nearestEntity(predicate)` searches observations. Entities expose `id`, `uuid`, `name` (registry name), `displayName`, `type` ('player', 'mob', 'orb', 'other', ...), `username` (players), position and health; `getDroppedItem()` reads a dropped stack, `getCustomName()` a text component. Use `Vec3` normally (`offset`, `minus`, `distanceTo`, `clone`); no custom vector library is required.

Block queries use local observations plus received columns. Null does not mean air. `await bot.waitForChunksToLoad()` waits for observation delivery, never forces chunk loading. Use bounded waits when the world must advance:

```js
for (let ticks = 0; ticks < 100 && !bot.entity.onGround; ticks += 5)
  await bot.waitForTicks(5);
if (!bot.entity.onGround) throw new Error('Still airborne');
```

Await native operations: `lookAt(point)`, `dig(block, forceLook?, digFace?)`, `placeBlock(referenceBlock, faceVector)`, `placeEntity(referenceBlock, faceVector)`, `activateBlock(block)`, `activateEntity(entity)`, `equip(item, destination)`, `unequip(destination)`, `consume()`, `fish()`, `sleep(bed)` and `wake()`. Dig/place/interact require reach and do not approach automatically. Check `canDigBlock(block)` before digging. Equipment destinations: `hand`, `off-hand`, `head`, `torso`, `legs`, `feet`. `attack(entity)`, `activateItem(offhand?)`, `deactivateItem()`, `mount(entity)`, `dismount()` and `updateSign(block, text, back?)` queue native work; `await bot.waitForTicks(1)` flushes it before inspection. Open the native sign editor first (at most four lines of 45 characters). Misuse of synchronous helpers such as `updateSign` or `dismount` emits an `error` event instead of throwing.

Use `await bot.openContainer(blockOrEntity)` then `window.containerItems()`, `await window.withdraw(type, metadata, count, nbt?)`, `await window.deposit(type, metadata, count, nbt?)` and `await window.close()`. Use `null` metadata for ordinary modern items. Item observations include name/type/count, slot and modern components; native stacking preserves exact components. Filters by item type alone may select several component variants. `bot.toss(type, metadata, count)` deliberately drops items.

`recipesFor(type, metadata, minResultCount, craftingTable?)` finds affordable recipes; `recipesAll(type, metadata, craftingTable?)` includes unaffordable ones. `await bot.craft(recipe, repetitions, craftingTable?)` performs native crafting. Recipe results/ingredients carry ID/count; inspect inventory afterwards. Pinned vanilla recipe data is a planning hint, not proof that the current native menu accepts a recipe.

Workstations: `openFurnace(block)` returns a window with `inputItem()`/`fuelItem()`/`outputItem()`, `putInput`/`putFuel(type, metadata, count)` and `takeInput`/`takeFuel`/`takeOutput()`. `openEnchantmentTable(block)` provides `targetItem()`, `putTargetItem(item)`, `putLapis(item)`, `enchant(choice)` and `takeTargetItem()`. `openAnvil(block)` provides `rename(item, name)` and `combine(first, second, name?)`. `openVillager(entity)` exposes offers and `trade(index, times)`. All return native window handles; close them when done. [Inventory reference](references/inventory.md) covers exact slots, components, offers and failure recovery.

`bot.chat(text)` and `bot.whisper(username, text)` queue messages (split at newlines and 256 characters; a leading `/` is a command). Subscribe with `bot.on('message', (message, position, sender, verified) => ...)`; use `message.toString()`, and `bot.off` to remove listeners. `awaitMessage(...stringsOrRegexes, timeoutMs = 20000)` resolves with the first message text that equals a string or matches a RegExp, and rejects on timeout. `writeBook(slot, pages)` and `signBook(slot, pages, author, title)` use native inventory operations; the author argument is ignored and the native body is the author. [Observation reference](references/observations.md) covers advanced queries, transient events, chat patterns, registries and presentation data.

Default deadline is 120 seconds, maximum 300; CPU, memory and output are bounded. Native permissions remain authoritative: survival follows normal inventory/reach rules, creative permits creative edits/flight, and only Creative + commands permits commands. The current script owns a lease in its world session; physical tools cannot take over until it ends. [Advanced actions](references/actions.md) covers direct controls, creative flight/items, precise placement, vehicles and command operations.

Known action failures reject with details; work already completed remains. Native permission denials such as `creative_mode_required` terminate the execution and can bypass script try/catch; inspect the tool report. Inventory failures can leave items on the cursor or partial trades/crafts. Inspect `currentWindow`, slots and the error before deciding what to do. A lost/malformed native reply is an unknown outcome: execution stops, and must not be automatically replayed. World/body changes and stream loss terminate execution. The tool report includes action observations and whether body release was confirmed; compilation or a method's presence is not gameplay evidence.
