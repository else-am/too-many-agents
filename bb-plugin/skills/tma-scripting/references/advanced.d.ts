// Available API outside the main skill. These declarations extend the core
// interfaces in SKILL.md. Behavior notes live in actions.md, inventory.md and
// observations.md.

interface Bot {
  // Body and world
  quickBarSlot: number | null;
  settings: { mainHand: 'left' | 'right' };
  setSettings(options: { mainHand: 'left' | 'right' }): void;
  fireworkRocketDuration: number;
  rainState: number;
  thunderState: number;
  version: string;
  nativeRegistries: Record<string, string[]>; // world-specific component holder IDs

  // Observation
  findBlock(options: Parameters<Bot['findBlocks']>[0] & { useExtraInfo?: boolean | ((block: Block) => boolean) }): Block | null;
  blockAt(position: Vec3, extraInfos?: boolean): Block | null;
  entityAtCursor(maxDistance?: number): Entity | null; // default 3.5
  players: Record<string, { username: string; uuid: string; displayName: ChatMessage; entity?: Entity }>;

  // Mining
  digTime(block: Block): number; // ms estimate; native mining decides
  stopDigging(): void;
  targetDigBlock: Block | null;

  // Look and direct controls (setters queue; flush with waitForTicks(1))
  look(yaw: number, pitch: number, force?: boolean): Promise<void>; // radians
  setControlState(control: Control, state: boolean): void;
  getControlState(control: Control): boolean;
  clearControlStates(): void;
  moveVehicle(left: number, forward: number): void; // each in [-1, 1]
  elytraFly(): Promise<void>;

  // Precise interaction
  activateBlock(block: Block, direction?: Vec3, cursorPos?: Vec3): Promise<void>;
  activateEntityAt(entity: Entity, position: Vec3): Promise<void>;
  swingArm(arm?: 'left' | 'right', showHand?: boolean): void;
  _genericPlace(reference: Block, face: Vec3, options?: PlaceOptions): Promise<Vec3>;
  _placeBlockWithOptions(reference: Block, face: Vec3, options?: PlaceOptions): Promise<void>;
  _placeEntityWithOptions(reference: Block, face: Vec3, options?: PlaceOptions): Promise<Entity>;

  // Exact inventory
  clickWindow(slot: number, button: number, mode: 0 | 1 | 2 | 3 | 4): Promise<void>; // PICKUP, QUICK_MOVE, SWAP, CLONE, THROW
  moveSlotItem(source: number, destination: number): Promise<void>;
  setQuickBarSlot(slot: number): void; // 0..8
  tossStack(item: Item): Promise<void>;
  openBlock(block: Block, direction?: Vec3, cursorPos?: Vec3): Promise<Window>;
  openEntity(entity: Entity): Promise<Window>;
  openContainer(target: Block | Entity, direction?: Vec3, cursorPos?: Vec3): Promise<Window>;

  // Creative (requires native creative permission)
  creative: {
    startFlying(): void;
    stopFlying(): void;
    flyTo(position: Vec3): Promise<void>; // straight line, collision checked; no routing
    setInventorySlot(slot: number, item: Item | null, waitTimeout?: number): Promise<void>; // slots 1..45
    clearInventory(): Promise<void>;
  };
  setCommandBlock(position: Vec3, command: string, options?: { mode?: 0 | 1 | 2; trackOutput?: boolean; conditional?: boolean; alwaysActive?: boolean }): void;
  tabComplete(text: string, assumeCommand?: boolean, sendBlockInSight?: boolean, timeoutMs?: number): Promise<unknown[]>;

  // Chat patterns (matches emit `chat:<name>`)
  addChatPattern(name: string, pattern: RegExp, options?: { repeat?: boolean; parse?: boolean }): number;
  addChatPatternSet(name: string, patterns: RegExp[], options?: { repeat?: boolean; parse?: boolean }): number;
  removeChatPattern(nameOrId: string | number): void;
  chatPatterns: unknown[];

  // Presentation snapshots
  scoreboards: Record<string, ScoreBoard>;
  scoreboard: Record<string | number, ScoreBoard>; // also list, sidebar, belowName
  teams: Record<string, Team>;
  bossBars: BossBar[];
  tablist: { header: ChatMessage; footer: ChatMessage };

  // Events
  once(event: string, listener: Function): Bot;
  on(event: 'health' | 'death' | 'forcedMove' | 'physicsTick' | 'title_clear', listener: () => void): Bot;
  on(event: 'entitySpawn' | 'entityGone' | 'entityDead' | 'entityEat' | 'entityEatingGrass' | 'entitySwingArm'
    | 'entityCriticalEffect' | 'entityMagicCriticalEffect' | 'entityShakingOffWater' | 'entityTaming' | 'entityTamed',
    listener: (entity: Entity) => void): Bot;
  on(event: 'entityHurt', listener: (victim: Entity, attacker?: Entity) => void): Bot;
  on(event: 'playerCollect', listener: (collector: Entity, collected: Entity) => void): Bot;
  on(event: 'soundEffectHeard', listener: (name: string, position: Vec3, volume: number, pitch: number) => void): Bot;
  on(event: 'particle', listener: (particle: Particle) => void): Bot;
  on(event: 'blockUpdate', listener: (before: Block | null, after: Block | null) => void): Bot;
  on(event: 'blockEntityData', listener: (block: Block) => void): Bot;
  on(event: 'blockBreakProgressObserved', listener: (block: Block, stage: number, breaker?: Entity) => void): Bot;
  on(event: 'blockBreakProgressEnd', listener: (block: Block, breaker?: Entity) => void): Bot;
  on(event: 'noteHeard', listener: (block: Block, instrument: { id: number; name: string }, note: number) => void): Bot;
  on(event: 'pistonMove', listener: (block: Block, action: number, parameter: number) => void): Bot;
  on(event: 'chestLidMove', listener: (block: Block, openCount: number, partner: Block | null) => void): Bot;
  on(event: 'title', listener: (text: string, type: string) => void): Bot;
  on(event: 'title_times', listener: (fadeIn: number, stay: number, fadeOut: number) => void): Bot;
  on(event: 'error', listener: (error: Error) => void): Bot;
  on(event: `chat:${string}`, listener: (matches: unknown) => void): Bot;
}

interface Experience { points: number; progress: number }
interface Game { hardcore: boolean; minY: number; height: number }
interface Time { day: number; moonPhase: number; doDaylightCycle: boolean; age: number; bigAge: bigint; bigTime: bigint }

interface Registry {
  items: Record<number, { id: number; name: string; displayName: string; stackSize: number }>;
  attributesByName: Record<string, unknown>;
  biomesByName: Record<string, { id: number; name: string }>;
  effects: Record<number, { id: number; name: string }>;
  effectsByName: Record<string, { id: number; name: string }>;
  enchantmentsByName: Record<string, { id: number; name: string; maxLevel: number }>;
  entitiesByName: Record<string, { id: number; name: string; type: string }>;
  foodsByName: Record<string, unknown>;
  blockLoot: Record<string, unknown>;
  entityLoot: Record<string, unknown>;
  particles: Record<number, { id: number; name: string }>;
}

type Control = 'forward' | 'back' | 'left' | 'right' | 'jump' | 'sprint' | 'sneak';

interface PlaceOptions {
  offhand?: boolean;
  forceLook?: boolean | 'ignore';
  half?: 'top' | 'bottom';
  delta?: Vec3;          // block-local hit point, each axis 0..1
  swingArm?: 'left' | 'right';
  showHand?: boolean;
}

interface Entity {
  velocity: Vec3;
  yaw: number;
  pitch: number;
  width: number;
  height: number;
  eyePosition?: Vec3;
  heldItem: Item | null;
  mainHand?: 'left' | 'right';
  passengers: Entity[];
  vehicle: Entity | null;
  metadata: unknown[];
  attributes?: Record<string, unknown>;
  count?: number;        // experience orb value
}

interface Block {
  stateId: number;
  hardness: number;
  light: number;
  skyLight: number;
  transparent: boolean;
  isWaterlogged: boolean;
  material: string;
  boundingBox: 'block' | 'empty';
  shapes: number[][];
  drops: unknown[];
  canHarvest(heldItemType: number | null): boolean;
}

interface Item {
  metadata: number;
  components: unknown[];
  removedComponents: unknown[];
  componentMap: Map<string, { type: string; data: unknown }>;
  nbt: unknown;
  repairCost: number;
  spawnEggMobName: string;
  blocksCanDestroy: unknown;
  blocksCanPlaceOn: unknown;
}

interface Window {
  type: string;
  title: string;
  slots: (Item | null)[];
  selectedItem: Item | null; // cursor stack
  inventoryStart: number;
  inventoryEnd: number;      // exclusive
  hotbarStart: number;
  craftingResultSlot: number;
  count(itemType: number, metadata?: number | null): number;
  containerCount(itemType: number, metadata?: number | null): number;
  findInventoryItem(typeOrName: number | string, metadata?: number | null, notFull?: boolean): Item | null;
  findContainerItem(typeOrName: number | string, metadata?: number | null, notFull?: boolean): Item | null;
  firstEmptyInventorySlot(hotbarFirst?: boolean): number | null;
  firstEmptyHotbarSlot(): number | null;
  firstEmptyContainerSlot(): number | null;
  emptySlotCount(): number;
  withdraw(itemType: number, metadata: number | null, count?: number, nbt?: unknown): Promise<void>;
  deposit(itemType: number, metadata: number | null, count?: number, nbt?: unknown): Promise<void>;
  on(event: 'updateSlot', listener: (slot: number, before: Item | null, after: Item | null) => void): Window;
  on(event: 'close' | 'ready', listener: () => void): Window;
  once(event: 'updateSlot' | 'close' | 'ready', listener: Function): Window;
  off(event: 'updateSlot' | 'close' | 'ready', listener: Function): Window;
}

interface Furnace {
  fuelSeconds: number;
  progressSeconds: number;   // remaining
  totalProgress: number;     // ticks
}

interface EnchantmentTable {
  xpseed: number;
}

interface Villager {
  selectedTrade: Trade | null;
}

interface Trade {
  inputItem1: Item;
  nbTradeUses: number;
  maximumNbTradeUses: number;
  demand: number;
  specialPrice: number;
  priceMultiplier: number;
  xp: number;
  rewardExp: boolean;
}

interface Recipe {
  outShape: ({ id: number; count: number } | null)[][] | null;
  delta: { id: number; metadata: number | null; count: number }[];
}

interface Particle {
  id: number;
  name: string;
  position: Vec3;
  offset: Vec3;
  count: number;
  movementSpeed: number;
  longDistanceRender: boolean;
}

interface ScoreBoard {
  name: string;
  title: string;
  items: { name: string; value: number; displayName: ChatMessage }[];
}

interface Team {
  team: string;
  name: ChatMessage;
  members: string[];
  friendlyFire: number;
  nameTagVisibility: string;
  collisionRule: string;
  color: string;
  prefix: ChatMessage;
  suffix: ChatMessage;
}

interface BossBar {
  entityUUID: string;
  title: ChatMessage;
  health: number;
  color: string;
  dividers: number;
  shouldDarkenSky: boolean;
  isDragonBar: boolean;
  createFog: boolean;
}

// new Item(type, count) builds a local stack for creative.setInventorySlot.
// ChatMessage and MessageBuilder are the prismarine-chat classes (toString, toMotd, MessageBuilder.fromString ...).
// Timers: setTimeout / clearTimeout.
