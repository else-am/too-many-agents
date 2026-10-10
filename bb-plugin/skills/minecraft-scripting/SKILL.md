---
name: minecraft-scripting
description: "Write minecraft_run scripts that drive your Minecraft NPC body: find blocks and entities, move with native navigation, mine, place, craft, use containers and workstations, fight, chat, and inspect the results. Use this whenever you act in or inspect the Minecraft world through code, including quick one-off checks, and whenever you are tempted to use Mineflayer APIs such as pathfinder."
---

Use `minecraft_run` with an async JavaScript function body. `bot` is already initialized; return JSON and use `console.log` for brief diagnostics. Globals are `bot`, `Vec3`, `Item`, `ChatMessage` and `MessageBuilder`. There are no imports, filesystem or network.

This is a deliberate Mineflayer subset for a singleplayer NPC, not a player connection. Your body is a Minecraft mob with its own navigation, so **Pathfinder is not implemented**: use `await bot.moveTo(position)` and `bot.stopMoving()` instead of `bot.pathfinder` and goals. Mineflayer plugins (`loadPlugin`, collectBlock, pvp, tool, autoEat) are unavailable, and so are `bot.health`/`bot.food` (use `bot.entity.health`). Reading a member outside the subset throws with a hint. The types below are the API; prefer them to Mineflayer memory.

Work in short scripts: observe, act, then return what you need to decide the next step. Nothing is retried for you and a failure leaves completed work in place, so a few steps per call keeps outcomes easy to read and recover from. Observed objects update during awaits; copy values such as `position.clone()` for a before-state.

```js
const log = bot.findBlocks({ matching: bot.registry.blocksByName.oak_log.id, maxDistance: 16, count: 1 })[0];
if (!log) return 'no logs nearby';
await bot.moveTo(log.offset(1, 0, 0));
await bot.dig(bot.blockAt(log));
return { position: bot.entity.position, items: bot.inventory.items().map(i => `${i.name} x${i.count}`) };
```

When the world must advance, wait with a bound. An unbounded loop just runs into the script deadline and tells you nothing:

```js
for (let ticks = 0; ticks < 100 && !bot.entity.onGround; ticks += 5) await bot.waitForTicks(5);
```

Stopping a move rejects it with `MovementStopped`; attach a handler first:

```js
const moving = bot.moveTo(bot.entity.position.offset(12, 0, 0))
  .catch(error => { if (error.code !== 'MovementStopped') throw error; });
await bot.waitForTicks(5);
await bot.stopMoving();
await moving;
```

## Rules the types cannot express

- **Movement** works in loaded, simulated chunks inside your permitted box, targets within 64 blocks, at most 60 s of game time. Arrival means within 0.9 blocks of the requested cell. It does not dig, build, teleport or load chunks; choose a standable destination. Dismount, wake and end flight/gliding first. Different species navigate differently. Starting during another native action can reject with `action_already_running_cancel_or_wait`.
- **Reach**: dig/place/activate/open need reach and do not approach. Workstations are used from their top face; a short body may need it unobstructed.
- **Queued calls** (`attack`, `activateItem`, `deactivateItem`, `mount`, `dismount`, `updateSign`, `chat`, `whisper`) return immediately; `await bot.waitForTicks(1)` before inspecting. Misuse of these emits an `error` event instead of throwing.
- **Block reads** cover observed cells only. `null` from `blockAt` means unknown, not air. `waitForChunksToLoad` waits for delivery and never forces loading.
- **Items**: use `null` metadata for modern items. Type-only filters can match several component variants. `toss` drops items deliberately. Recipes are pinned planning data; the native menu decides.
- **Limits**: deadline 120 s by default, 300 s max. Code may run at most 1 s without awaiting, or it stops with `CpuLimit`; memory and output are bounded. Your script holds the body until it ends.
- **Permissions** are native: survival follows normal rules, creative allows creative edits/flight, only Creative + commands allows commands. A denial such as `creative_mode_required` ends the script and can bypass try/catch.
- **Failures** reject with `error.code` and details; completed work remains. Inventory failures can leave items on the cursor or partial crafts/trades, so inspect `currentWindow` and slots. A lost native reply is an unknown outcome: execution stops. Inspect the world before acting again, because blindly replaying could repeat a mutation that already happened. Errors report the failing line of your script.

## Core API

```ts
declare const bot: Bot;

interface Bot {
  // Body and world
  entity: Entity;                    // your NPC body
  username: string;
  entities: Record<number, Entity>;  // observed entities by id
  heldItem: Item | null;
  inventory: Window;
  currentWindow: Window | null;
  usingHeldItem: boolean;
  vehicle: Entity | null;
  experience: Experience;
  game: Game;
  time: Time;
  isRaining: boolean;
  spawnPoint: Vec3 | null;          // null until observed
  registry: Registry;

  // Observation
  blockAt(position: Vec3): Block | null;
  findBlocks(options: {
    matching: number | number[] | ((block: Block) => boolean);
    maxDistance?: number;            // default 16
    count?: number;                  // default 1
    point?: Vec3;                    // default: your position
  }): Vec3[];                        // positions, nearest first
  blockAtCursor(maxDistance?: number, matcher?: (block: Block) => boolean, entity?: Entity): Block | null;
  canSeeBlock(block: Block): boolean;
  nearestEntity(predicate?: (entity: Entity) => boolean): Entity | null;
  waitForChunksToLoad(): Promise<void>;
  waitForTicks(ticks: number): Promise<void>;

  // Movement (native navigation; rejects with MovementFailed | MovementStopped | MovementAlreadyRunning)
  moveTo(position: Vec3): Promise<void>;
  stopMoving(): Promise<void>;       // stops script movement and controls; keeps creative flight
  lookAt(point: Vec3, force?: boolean): Promise<void>;
  mount(entity: Entity): void;
  dismount(): void;

  // Blocks
  canDigBlock(block: Block): boolean;
  dig(block: Block, forceLook?: boolean | 'ignore', digFace?: 'auto' | 'raycast' | Vec3): Promise<void>;
  placeBlock(referenceBlock: Block, faceVector: Vec3): Promise<void>;   // places the held block against a face
  placeEntity(referenceBlock: Block, faceVector: Vec3): Promise<Entity>; // boats, spawn eggs, armor stands
  activateBlock(block: Block): Promise<void>;

  // Entities and items in hand
  attack(entity: Entity): void;
  activateEntity(entity: Entity): Promise<void>;
  activateItem(offhand?: boolean): void;
  deactivateItem(): void;
  consume(): Promise<void>;
  fish(): Promise<void>;
  sleep(bed: Block): Promise<void>;
  wake(): Promise<void>;
  updateSign(block: Block, text: string, back?: boolean): void; // open the sign editor first; ≤4 lines of 45 chars

  // Inventory
  equip(item: Item | number, destination: EquipDestination): Promise<void>;
  unequip(destination: EquipDestination): Promise<void>;
  toss(itemType: number, metadata: number | null, count?: number): Promise<void>; // count default 1

  // Containers and workstations (close windows when done)
  openContainer(target: Block | Entity): Promise<Window>;
  openFurnace(block: Block): Promise<Furnace>;
  openEnchantmentTable(block: Block): Promise<EnchantmentTable>;
  openAnvil(block: Block): Promise<Anvil>;
  openVillager(entity: Entity): Promise<Villager>;

  // Crafting
  recipesFor(itemType: number, metadata: number | null, minResultCount: number | null, craftingTable: Block | null): Recipe[]; // affordable now
  recipesAll(itemType: number, metadata: number | null, craftingTable: Block | null): Recipe[];
  craft(recipe: Recipe, count?: number, craftingTable?: Block): Promise<void>; // count ≤ 256

  // Chat (split at newlines and 256 chars; a leading '/' is a command)
  chat(text: string): void;
  whisper(username: string, text: string): void;
  awaitMessage(...patterns: (string | RegExp | number)[]): Promise<string>; // trailing number = timeout ms, default 20000
  on(event: 'message', listener: (message: ChatMessage, position: 'chat' | 'system' | 'game_info', sender: string | null, verified: boolean) => void): Bot;
  off(event: string, listener: Function): Bot;
  writeBook(slot: number, pages: string[]): Promise<void>;
  signBook(slot: number, pages: string[], author: string, title: string): Promise<void>; // author ignored; your body signs
}

interface Experience { level: number }
interface Game { dimension: string; gameMode: 'survival' | 'creative'; difficulty: string }
interface Time { timeOfDay: number; isDay: boolean }
interface Registry {
  blocksByName: Record<string, { id: number; name: string; displayName: string }>;
  itemsByName: Record<string, { id: number; name: string; displayName: string; stackSize: number }>;
}

type EquipDestination = 'hand' | 'off-hand' | 'head' | 'torso' | 'legs' | 'feet';

interface Entity {
  id: number;
  uuid: string;
  name: string;                      // registry name, e.g. 'cow'
  displayName: string;
  type: 'player' | 'mob' | 'animal' | 'hostile' | 'passive' | 'water_creature' | 'ambient' | 'living' | 'projectile' | 'orb' | 'other';
  username?: string;                 // players
  position: Vec3;
  health?: number;
  onGround: boolean;
  isValid: boolean;                  // still observed
  alive?: boolean;
  crouching?: boolean;
  isSleeping?: boolean;
  isInWater?: boolean;
  isInLava?: boolean;
  elytraFlying?: boolean;
  airSupply?: number;
  maxAirSupply?: number;
  equipment: (Item | null)[];        // [mainHand, offHand, feet, legs, chest, head]
  effects: Record<number, { id: number; amplifier: number; duration: number }>;
  agent?: { id: string };            // another too-many-agents NPC
  getCustomName(): ChatMessage | null;
  getDroppedItem(): Item | null;     // for dropped item entities
}

interface Block {
  name: string;                      // e.g. 'oak_log'
  displayName: string;
  type: number;
  position: Vec3;
  diggable: boolean;
  biome: { name: string };
  blockEntity?: unknown;
  getProperties(): Record<string, string | number | boolean>;
}

interface Item {
  name: string;
  displayName: string;
  type: number;
  count: number;
  stackSize: number;
  slot: number;
  customName: unknown;               // text component or null
  customLore: unknown;               // text components or null
  enchants: unknown;                 // native enchantment component data
  durabilityUsed: number;
  maxDurability: number;
}

interface Window {
  items(): Item[];                   // your inventory part
  containerItems(): Item[];          // the container part
  withdraw(itemType: number, metadata: number | null, count?: number): Promise<void>; // count default 1
  deposit(itemType: number, metadata: number | null, count?: number): Promise<void>;
  close(): Promise<void>;
}

interface Furnace extends Window {
  inputItem(): Item | null; fuelItem(): Item | null; outputItem(): Item | null;
  fuel: number; progress: number;    // fractions 0..1
  putInput(itemType: number, metadata: number | null, count: number): Promise<void>;
  putFuel(itemType: number, metadata: number | null, count: number): Promise<void>;
  takeInput(): Promise<Item>; takeFuel(): Promise<Item>; takeOutput(): Promise<Item>;
}

interface EnchantmentTable extends Window {
  enchantments: { level: number; expected: { enchant: number; level: number } }[]; // 3 offers
  targetItem(): Item | null;
  putTargetItem(item: Item): Promise<void>;
  putLapis(item: Item): Promise<void>;
  enchant(choice: 0 | 1 | 2): Promise<Item>;
  takeTargetItem(): Promise<Item>;
}

interface Anvil extends Window {
  rename(item: Item, name: string): Promise<void>;                 // name ≤ 35 chars
  combine(first: Item, second: Item, name?: string): Promise<void>;
}

interface Villager extends Window {
  trades: Trade[];
  trade(index: number, times?: number): Promise<void>;             // times ≤ 256; omitted = all remaining
}

interface Trade { costA: Item; inputItem2: Item | null; outputItem: Item; tradeDisabled: boolean }

interface Recipe {
  result: { id: number; count: number };
  inShape: ({ id: number; count: number } | null)[][] | null;
  ingredients: { id: number; count: number }[] | null;
  requiresTable: boolean;
}

interface ChatMessage { toString(): string }
// Vec3 is the standard vec3 library: x, y, z, offset, plus, minus, distanceTo, floored, equals, clone.
```

## More

The core API covers most tasks. Open a reference only when a task needs something it lacks:

- [advanced.d.ts](references/advanced.d.ts): types for everything else available. Check it before concluding something is impossible.
- [actions.md](references/actions.md): when you need direct movement controls, precise placement (hit point, offhand, slab half), vehicles, creative flight/items or commands.
- [inventory.md](references/inventory.md): when you need exact slots, a specific component variant, merchant details, or recovery after a failed inventory operation.
- [observations.md](references/observations.md): when you need events (sounds, particles, block updates, entity hurt), chat patterns, registries or scoreboards.
