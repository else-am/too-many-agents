// Planner scenarios, defined before the native-body adaptation. No game/server is started.
// node tools/mineflayer-reference/movements.mjs /path/to/reference/node_modules
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const dependencies = resolve(process.argv[2] ?? fileURLToPath(new URL('./node_modules', import.meta.url)));
const reference = createRequire(pathToFileURL(resolve(dependencies, '../package.json')));
const { Vec3 } = reference('vec3');
const Upstream = reference('mineflayer-pathfinder/lib/movements');
const Move = reference('mineflayer-pathfinder/lib/move');
const AStar = reference('mineflayer-pathfinder/lib/astar');
const { GoalBlock } = reference('mineflayer-pathfinder/lib/goals');
assert.equal(reference('mineflayer-pathfinder/package.json').version, '2.4.5');
const registry = reference('prismarine-registry')('1.21.1');
const Block = reference('prismarine-block')(registry);
const normal = { stepHeight: 0.6, jumpHeight: 1.2, canJump: true, canSwim: true,
  maxJumpDistance: 2, maxSprintJumpDistance: 4 };
const origin = () => new Move(0, 0, 0, 16, 0);
const east = { x: 1, z: 0 };
const state = (name, properties = {}) => Block.fromProperties(name, properties, 0).stateId;

// Water cases authored before correcting the pinned graph. These are deliberate
// source differences from 2.4.5, not a claim that upstream produces these routes.
const waterContracts = [
  ['pure-water entry and bank exit', 'Real two-deep channel, no inventory/dig/parkour/towers: enter the immediately lower surface cell, traverse, then exit onto the opposite bank. Pinned upstream misses the entry goal.'],
  ['adjacent vertical swimming', 'Ascend and descend between existing liquid cells with no placed support. Pinned upstream emits neither vertical edge; native canSwim must authorize both.'],
  ['water permissions and costs', 'Missing/false canSwim, avoided water, exclusions and avoided entities forbid the new edges; liquidCost and entityCost affect them. Drop policy still limits dry water entry.'],
  ['water body clearance', 'Short bodies fit a low water tunnel; tall/wide bodies reject intersecting roof/side shapes, including swept ascent headroom and unknown blocks. No unlisted digs or blocks.'],
  ['water surface is not free flight', 'Vertical swimming requires liquid at both endpoints. An unsupported air cell above the surface is not an ascent edge, even with towers enabled and zero materials.'],
];

function fixture({ width = 0.6, height = 1.8, nativeBody, floor = -1 } = {}) {
  const blocks = new Map();
  const bot = {
    registry, game: { minY: -16 }, entities: {},
    entity: { width, height, position: new Vec3(0.5, 0, 0.5), effects: {} },
    inventory: { items: () => [{ type: registry.itemsByName.cobblestone.id, count: 16 }] },
    pathfinder: { bestHarvestTool: () => null },
    blockAt(p) {
      p = p.floored();
      if (Math.abs(p.x) > 12 || Math.abs(p.z) > 12 || p.y < -16 || p.y > 10) return null;
      const id = blocks.get(p.toString()) ?? state(p.y === floor ? 'stone' : 'air');
      const block = Block.fromStateId(id, 0); block.position = p; return block;
    },
  };
  if (nativeBody !== undefined) bot.nativeBody = nativeBody;
  const set = (x, y, z, name, properties) => blocks.set(new Vec3(x, y, z).toString(), state(name, properties));
  const wall = (x1, x2, y1, y2, z1, z2, name = 'stone') => {
    for (let x = x1; x <= x2; x++) for (let y = y1; y <= y2; y++) for (let z = z1; z <= z2; z++) set(x, y, z, name);
  };
  return { bot, set, wall };
}
const moveAt = (moves, x, y, z) => moves.find(m => m.x === x && m.y === y && m.z === z);
function generate(movements, method, node = origin(), dir = east) {
  const result = [];
  if (method === 'getMoveUp' || method === 'getMoveDown') movements[method](node, result);
  else movements[method](node, dir, result);
  return result;
}
function normalize(moves) {
  return moves.map(m => ({ xyz: [m.x, m.y, m.z], cost: m.cost, remaining: m.remainingBlocks,
    breaks: m.toBreak.map(p => [p.x, p.y, p.z]).sort(), places: JSON.parse(JSON.stringify(m.toPlace)), parkour: m.parkour }))
    .sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b)));
}

// Independent reference comparisons: same real Block class, world, materials and policies.
const comparisons = [
  ['flat ground', () => {}],
  ['breakable obstacle', f => f.wall(1, 1, 0, 1, 0, 0)],
  ['half slab', f => f.set(1, 0, 0, 'stone_slab', { type: 'bottom', waterlogged: false })],
  ['one-block jump', f => f.set(1, 0, 0, 'stone')],
  ['two-block drop', f => { f.set(1, -1, 0, 'air'); f.set(1, -3, 0, 'stone'); }],
  ['bridge', f => f.set(1, -1, 0, 'air')],
  ['water landing', f => { f.set(1, -1, 0, 'air'); f.set(1, -3, 0, 'water'); }],
  ['parkour gap', f => { for (let x = 1; x <= 2; x++) f.set(x, -1, 0, 'air'); }],
  ['carpet', f => f.set(1, 0, 0, 'white_carpet')],
  ['stairs', f => f.set(1, 0, 0, 'stone_stairs', { facing: 'east', half: 'bottom', shape: 'straight', waterlogged: false })],
  ['ladder', f => { f.set(0, 0, 0, 'ladder', { facing: 'north', waterlogged: false }); f.set(0, 1, 0, 'ladder', { facing: 'north', waterlogged: false }); }],
  ['openable fence gate', f => f.set(1, 0, 0, 'oak_fence_gate', { facing: 'north', open: false, powered: false, in_wall: false }), m => { m.canOpenDoors = true; }],
  ['liquid and falling-block dig safety', f => { f.set(1, 0, 0, 'stone'); f.set(1, 1, 0, 'sand'); f.set(-1, 0, 0, 'stone'); f.set(-1, 0, 1, 'water'); }],
  ['cost multipliers', f => { f.set(1, 0, 0, 'dirt'); f.set(-1, -1, 0, 'air'); }, m => { m.digCost = 2; m.placeCost = 4; m.liquidCost = 3; m.entityCost = 5; }],
  ['enchanted harvest tool cost', f => {
    f.set(1, 0, 0, 'stone'); f.bot.pathfinder.bestHarvestTool = () => ({ type: registry.itemsByName.diamond_pickaxe.id,
      nbt: { type: 'compound', value: { Enchantments: { type: 'list', value: { type: 'compound', value: [
        { name: { type: 'string', value: 'efficiency' }, lvl: { type: 'short', value: 3 } },
      ] } } } } });
  }],
  ['no-dig policy', f => { f.wall(1, 1, 0, 1, 0, 0); }, m => { m.canDig = false; }],
  ['exclusion callbacks', () => {}, m => {
    m.exclusionAreasStep.push(b => b.position?.x === 1 ? 100 : 0);
    m.exclusionAreasPlace.push(b => b.position?.z === -1 ? 100 : 0);
    m.exclusionAreasBreak.push(b => b.position?.x === -1 ? 100 : 0);
  }],
  ['entity collision index', f => {
    f.bot.entities.other = { name: 'cow', position: new Vec3(1.5, 0, 0.5), width: 0.9, height: 1.4 };
  }],
];

// Body scenarios assert externally meaningful geometry/policy, not a copy of the implementation.
const scenarios = [
  ['short body uses a low tunnel without digging its roof', Adapter => {
    const f = fixture({ height: 0.8, nativeBody: normal }); f.wall(-1, 5, 1, 1, -1, 1, 'bedrock');
    const m = new Adapter(f.bot); m.canDig = false;
    const result = new AStar(origin(), m, new GoalBlock(4, 0, 0), 1000, 1000).compute();
    assert.equal(result.status, 'success'); assert(result.path.every(n => !n.toBreak.length));
    const u = new Upstream(f.bot); u.canDig = false;
    assert(!moveAt(u.getNeighbors(origin()), 1, 0, 0));
  }],
  ['tall body plans required third-level clearance digs', Adapter => {
    const f = fixture({ height: 2.8, nativeBody: normal }); f.set(1, 2, 0, 'stone');
    const m = new Adapter(f.bot); const move = moveAt(generate(m, 'getMoveForward'), 1, 0, 0);
    assert(move); assert(move.toBreak.some(p => p.equals(new Vec3(1, 2, 0))));
    m.canDig = false; assert.equal(generate(m, 'getMoveForward').length, 0);
    m.canDig = true; m.exclusionAreasBreak.push(b => b.position?.y === 2 ? 100 : 0);
    assert.equal(generate(m, 'getMoveForward').length, 0);
  }],
  ['wide body clears side cells and refuses a bedrock doorway', Adapter => {
    const f = fixture({ width: 1.4, nativeBody: normal }); f.set(2, 0, 1, 'stone');
    const m = new Adapter(f.bot); const move = moveAt(generate(m, 'getMoveForward'), 1, 0, 0);
    assert(move?.toBreak.some(p => p.equals(new Vec3(2, 0, 1))));
    f.set(2, 0, 1, 'bedrock'); assert.equal(generate(m, 'getMoveForward').length, 0);
  }],
  ['short/tall jump headroom follows body height', Adapter => {
    const f = fixture({ height: 0.8, nativeBody: normal }); f.set(1, 0, 0, 'stone'); f.wall(0, 1, 2, 2, 0, 0, 'bedrock');
    const m = new Adapter(f.bot); m.canDig = false; assert(moveAt(generate(m, 'getMoveJumpUp'), 1, 1, 0));
    f.bot.entity.height = 1.8; assert.equal(generate(m, 'getMoveJumpUp').length, 0);
  }],
  ['step capability permits a half slab without granting jump', Adapter => {
    const f = fixture({ nativeBody: { ...normal, canJump: false, jumpHeight: 0 } });
    f.set(1, 0, 0, 'stone_slab', { type: 'bottom', waterlogged: false });
    const m = new Adapter(f.bot); m.canDig = false; assert(moveAt(generate(m, 'getMoveJumpUp'), 1, 1, 0));
    f.set(1, 0, 0, 'stone'); assert.equal(generate(m, 'getMoveJumpUp').length, 0);
  }],
  ['stepping off a slab also needs clearance above the source body', Adapter => {
    const f = fixture({ nativeBody: normal }); f.bot.entity.position.y = -0.5;
    f.set(0, -1, 0, 'stone_slab', { type: 'bottom', waterlogged: false });
    f.set(0, 1, 0, 'stone_slab', { type: 'top', waterlogged: false });
    const m = new Adapter(f.bot); m.canDig = false; assert.equal(generate(m, 'getMoveForward').length, 0);
    m.canDig = true; const move = moveAt(generate(m, 'getMoveForward'), 1, 0, 0);
    assert(move?.toBreak.some(p => p.equals(new Vec3(0, 1, 0))));
  }],
  ['tall drop needs shaft clearance; drop limits retain meaning', Adapter => {
    const f = fixture({ height: 2.8, nativeBody: normal }); f.set(1, -1, 0, 'air'); f.set(1, -4, 0, 'stone'); f.set(1, 2, 0, 'bedrock');
    const m = new Adapter(f.bot); m.canDig = false; assert.equal(generate(m, 'getMoveDropDown').length, 0);
    f.set(1, 2, 0, 'air'); assert(moveAt(generate(m, 'getMoveDropDown'), 1, -3, 0));
    m.maxDropDown = 2; assert.equal(generate(m, 'getMoveDropDown').length, 0);
  }],
  ['native parkour requires authorized horizontal reach and headroom', Adapter => {
    const f = fixture({ height: 0.8, nativeBody: { ...normal, maxJumpDistance: undefined, maxSprintJumpDistance: undefined } });
    f.set(1, -1, 0, 'air'); f.wall(0, 2, 2, 2, 0, 0, 'bedrock');
    const m = new Adapter(f.bot); assert.equal(generate(m, 'getMoveParkourForward').length, 0);
    f.bot.nativeBody.maxSprintJumpDistance = 2; assert(moveAt(generate(m, 'getMoveParkourForward'), 2, 0, 0));
    f.bot.entity.height = 1.8; assert.equal(generate(m, 'getMoveParkourForward').length, 0);
    f.bot.entity.height = 0.8; m.allowSprinting = false; assert.equal(generate(m, 'getMoveParkourForward').length, 0);
    f.bot.nativeBody.maxJumpDistance = 2; assert(moveAt(generate(m, 'getMoveParkourForward'), 2, 0, 0));
  }],
  ['diagonal liquid descent cannot bypass canSwim', Adapter => {
    const f = fixture({ nativeBody: { ...normal, canSwim: false } }); f.set(1, -1, 1, 'water');
    const m = new Adapter(f.bot); assert.equal(generate(m, 'getMoveDiagonal', origin(), {x: 1, z: 1}).length, 0);
    f.bot.nativeBody.canSwim = true; assert(moveAt(generate(m, 'getMoveDiagonal', origin(), {x: 1, z: 1}), 1, -1, 1));
  }],
  ['carpet still requires sufficient step or jump height', Adapter => {
    const f = fixture({ nativeBody: { canJump: false, canSwim: true, stepHeight: 0, jumpHeight: 0 } }); f.set(1, 0, 0, 'white_carpet');
    const m = new Adapter(f.bot); assert.equal(generate(m, 'getMoveForward').length, 0);
    f.bot.nativeBody.stepHeight = 0.1; assert(moveAt(generate(m, 'getMoveForward'), 1, 0, 0));
  }],
  ['liquid landing requires canSwim and respects liquid drop policy', Adapter => {
    const f = fixture({ nativeBody: { ...normal, canSwim: false } }); f.set(1, -1, 0, 'air'); f.set(1, -6, 0, 'water');
    const m = new Adapter(f.bot); assert.equal(generate(m, 'getMoveDropDown').length, 0);
    f.bot.nativeBody.canSwim = true; assert(moveAt(generate(m, 'getMoveDropDown'), 1, -6, 0));
    m.infiniteLiquidDropdownDistance = false; assert.equal(generate(m, 'getMoveDropDown').length, 0);
  }],
  ['bridge preserves material and placement exclusion constraints', Adapter => {
    const f = fixture({ width: 1.4, nativeBody: normal }); f.set(1, -1, 0, 'air');
    const m = new Adapter(f.bot); const move = moveAt(generate(m, 'getMoveForward'), 1, 0, 0);
    assert.equal(move?.toPlace.length, 1); assert.equal(move.remainingBlocks, 15);
    assert.equal(generate(m, 'getMoveForward', new Move(0, 0, 0, 0, 0)).length, 0);
    m.exclusionAreasPlace.push(() => 100); assert.equal(generate(m, 'getMoveForward').length, 0);
  }],
  ['wide-body clearance includes avoided adjacent entities', Adapter => {
    const f = fixture({ width: 1.4, nativeBody: normal });
    f.bot.entities.other = { name: 'cow', position: new Vec3(1.5, 0, 1.3), width: 0.6, height: 1.8 };
    const m = new Adapter(f.bot); m.entitiesToAvoid.add('cow'); m.updateCollisionIndex();
    assert.equal(generate(m, 'getMoveForward').length, 0);
    m.allowEntityDetection = false; assert(moveAt(generate(m, 'getMoveForward'), 1, 0, 0));
  }],
  ['tall jump clears the new source and destination headroom', Adapter => {
    const f = fixture({ height: 2.8, nativeBody: normal });
    f.set(1, 0, 0, 'stone'); f.set(0, 3, 0, 'stone'); f.set(1, 3, 0, 'stone');
    const m = new Adapter(f.bot); const move = moveAt(generate(m, 'getMoveJumpUp'), 1, 1, 0);
    assert(move); for (const x of [0, 1]) assert(move.toBreak.some(p => p.equals(new Vec3(x, 3, 0))));
  }],
  ['short body fits under a top slab but a taller body does not', Adapter => {
    const f = fixture({ height: 0.4, nativeBody: normal });
    f.set(1, 0, 0, 'stone_slab', { type: 'top', waterlogged: false });
    const m = new Adapter(f.bot); m.canDig = false; assert(moveAt(generate(m, 'getMoveForward'), 1, 0, 0));
    f.bot.entity.height = 0.6; assert.equal(generate(m, 'getMoveForward').length, 0);
  }],
  ['wide body cannot drop through a shaft narrower than its footprint', Adapter => {
    const f = fixture({ width: 1.4, nativeBody: normal }); f.set(1, -1, 0, 'air'); f.set(1, -3, 0, 'stone');
    const m = new Adapter(f.bot); m.canDig = false; assert.equal(generate(m, 'getMoveDropDown').length, 0);
    f.bot.entity.width = 0.6; assert(moveAt(generate(m, 'getMoveDropDown'), 1, -2, 0));
  }],
  ['tower clears tall-body overhead and obeys tower policy', Adapter => {
    const f = fixture({ height: 2.8, nativeBody: normal }); f.set(0, 3, 0, 'stone');
    const m = new Adapter(f.bot); const move = moveAt(generate(m, 'getMoveUp'), 0, 1, 0);
    assert(move?.toBreak.some(p => p.equals(new Vec3(0, 3, 0)))); assert.equal(move.toPlace[0].jump, true);
    m.allow1by1towers = false; assert.equal(generate(m, 'getMoveUp').length, 0);
  }],
  ['unknown headroom produces no edge rather than throwing', Adapter => {
    const f = fixture({ nativeBody: normal }); const read = f.bot.blockAt;
    f.bot.blockAt = p => p.y >= 1 ? null : read(p);
    const m = new Adapter(f.bot); m.canDig = false;
    assert.doesNotThrow(() => m.getNeighbors(origin())); assert.equal(generate(m, 'getMoveJumpUp').length, 0);
    f.bot.blockAt = p => p.x === 1 && p.y === 0 ? null : read(p);
    assert.equal(generate(m, 'getMoveForward').length, 0);
  }],
  ['wide body checks fence shapes protruding from the block below', Adapter => {
    const f = fixture({ width: 1.8, nativeBody: normal }); f.set(2, -1, 0, 'oak_fence');
    const m = new Adapter(f.bot); m.canDig = false; assert.equal(generate(m, 'getMoveForward').length, 0);
    m.canDig = true; const move = moveAt(generate(m, 'getMoveForward'), 1, 0, 0);
    assert(move?.toBreak.some(p => p.equals(new Vec3(2, -1, 0))));
  }],
  ['movement subclass and custom collision index remain effective', Adapter => {
    const f = fixture(); class Custom extends Adapter {
      getMoveForward(node, dir, neighbors) { if (dir.x !== 1) super.getMoveForward(node, dir, neighbors); }
    }
    const m = new Custom(f.bot); m.canDig = false; m.allowParkour = false; m.allow1by1towers = false;
    assert(!moveAt(m.getNeighbors(origin()), 1, 0, 0));
    m.entityIntersections['-1,0,0'] = 100;
    assert(!moveAt(m.getNeighbors(origin()), -1, 0, 0));
    m.clearCollisionIndex(); assert(moveAt(m.getNeighbors(origin()), -1, 0, 0));
  }],
  ['body defaults apply only to missing geometry', Adapter => {
    const f = fixture(); delete f.bot.entity.width; delete f.bot.entity.height;
    const m = new Adapter(f.bot); assert(moveAt(generate(m, 'getMoveForward'), 1, 0, 0));
    for (const width of [null, 0, -1, NaN, Infinity]) {
      f.bot.entity.width = width; assert.throws(() => generate(m, 'getMoveForward'), /width and height/);
    }
  }],
  ['missing native capabilities never inherit player abilities', Adapter => {
    const f = fixture({ nativeBody: {} }); f.set(1, 0, 0, 'stone');
    const m = new Adapter(f.bot); m.canDig = false; assert.equal(generate(m, 'getMoveJumpUp').length, 0);
    assert.equal(generate(m, 'getMoveUp').length, 0);
  }],
];

// Same channel as navigate-water.js, translated by (-9,+60,0) into the fixture.
function waterFixture(Adapter, options = {}) {
  const f = fixture({ nativeBody: normal, ...options, floor: -3 });
  f.bot.inventory.items = () => [];
  f.wall(1, 5, -2, -1, 0, 0, 'water');
  for (const x of [0, 6]) f.wall(x, x, -2, -1, 0, 0);
  for (const z of [-1, 1]) f.wall(0, 6, -2, 3, z, z);
  const m = new Adapter(f.bot);
  m.canDig = m.allowParkour = m.allow1by1towers = false;
  m.exclusionAreasStep.push(b => b.position && (b.position.z !== 0 || b.position.x < 0 || b.position.x > 6) ? 100 : 0);
  return { ...f, m };
}
const swimNode = (y = -2) => new Move(3, y, 0, 0, 0);
function route(m, start, end) {
  return new AStar(start, m, new GoalBlock(...end), 1000, 1000).compute();
}
function unedited(result) {
  assert.equal(result.status, 'success');
  assert(result.path.every(n => n.remainingBlocks === 0 && n.toBreak.length === 0 && n.toPlace.length === 0 && !n.parkour));
}
const waterEvidence = [];
const waterChecks = [
  Adapter => {
    const upstream = waterFixture(Upstream), actual = waterFixture(Adapter);
    const start = new Move(0, 0, 0, 0, 0);
    const pinned = route(upstream.m, start, [1, -1, 0]);
    const entry = route(actual.m, start, [1, -1, 0]);
    const sourceLanding = upstream.m.getLandingBlock(start, east).position;
    waterEvidence.push({ scenario: 'pure-water entry', upstream: pinned.status, adapter: entry.status,
      upstreamLanding: [sourceLanding.x, sourceLanding.y, sourceLanding.z], adapterPath: normalize(entry.path) });
    console.log(JSON.stringify(waterEvidence.at(-1)));
    assert.equal(pinned.status, 'noPath'); assert.equal(sourceLanding.y, -2);
    unedited(entry); assert.deepEqual(entry.path.map(n => [n.x, n.y, n.z]), [[1, -1, 0]]);
    const across = route(actual.m, new Move(1, -1, 0, 0, 0), [5, -1, 0]); unedited(across);
    const exit = route(actual.m, new Move(5, -1, 0, 0, 0), [6, 0, 0]); unedited(exit);
    // Pinned jump-up measures from the empty-shape water block below the feet.
    // The existing body adapter's _feetY already uses the actual fluid node Y.
    const upstreamExit = route(upstream.m, new Move(5, -1, 0, 0, 0), [6, 0, 0]);
    assert.equal(upstreamExit.status, 'noPath');
    unedited(route(actual.m, start, [6, 0, 0]));
    waterEvidence.push({ scenario: 'unedited traversal and existing adapter bank exit', upstreamExit: upstreamExit.status,
      across: normalize(across.path), exit: normalize(exit.path) });
    // Ordinary Mineflayer defaults get the same documented graph correction.
    unedited(route(waterFixture(Adapter, { nativeBody: undefined }).m, start, [1, -1, 0]));
  },
  Adapter => {
    for (const [method, y, targetY] of [['getMoveUp', -2, -1], ['getMoveDown', -1, -2]]) {
      const upstream = waterFixture(Upstream), actual = waterFixture(Adapter);
      const start = swimNode(y);
      assert.equal(generate(upstream.m, method, start).length, 0);
      const pinned = route(upstream.m, start, [3, targetY, 0]);
      const result = route(actual.m, start, [3, targetY, 0]);
      waterEvidence.push({ scenario: method, upstream: pinned.status, adapter: result.status, adapterPath: normalize(result.path) });
      console.log(JSON.stringify(waterEvidence.at(-1)));
      assert.equal(pinned.status, 'noPath'); unedited(result);
      assert.deepEqual(result.path.map(n => [n.x, n.y, n.z]), [[3, targetY, 0]]);
      const swimmer = waterFixture(Adapter, { nativeBody: { ...normal, canJump: false, jumpHeight: 0, stepHeight: 0 } });
      unedited(route(swimmer.m, start, [3, targetY, 0])); // swimming does not grant dry jumping
      assert.equal(generate(swimmer.m, 'getMoveJumpUp', new Move(5, -1, 0, 0, 0)).length, 0);
    }
  },
  Adapter => {
    for (const nativeBody of [{}, { ...normal, canSwim: false }]) {
      const { m } = waterFixture(Adapter, { nativeBody });
      assert.equal(generate(m, 'getMoveUp', swimNode()).length, 0);
      assert.equal(generate(m, 'getMoveDown', swimNode(-1)).length, 0);
      assert.equal(generate(m, 'getMoveDropDown', new Move(0, 0, 0, 0, 0)).length, 0);
    }
    for (const method of ['getMoveUp', 'getMoveDown']) {
      const start = swimNode(method === 'getMoveUp' ? -2 : -1);
      const { m, bot } = waterFixture(Adapter);
      const original = generate(m, method, start)[0]; assert(original);
      m.liquidCost += 3; assert.equal(generate(m, method, start)[0].cost, original.cost + 3);
      m.entityIntersections['3,-1,0'] = 1;
      m.entityCost = 4; assert(generate(m, method, start)[0].cost >= original.cost + 7);
      m.clearCollisionIndex(); m.exclusionAreasStep.push(b => b.position?.x === 3 ? 100 : 0);
      assert.equal(generate(m, method, start).length, 0);
      m.exclusionAreasStep.pop(); m.blocksToAvoid.add(registry.blocksByName.water.id);
      assert.equal(generate(m, method, start).length, 0);
      m.blocksToAvoid.delete(registry.blocksByName.water.id);
      bot.entities.other = { name: 'cow', position: new Vec3(3.5, -1, .5), width: .6, height: 1.8 };
      m.entitiesToAvoid.add('cow'); m.updateCollisionIndex();
      assert.equal(generate(m, method, start).length, 0);
    }
    const { m } = waterFixture(Adapter); m.maxDropDown = 0; m.infiniteLiquidDropdownDistance = false;
    assert.equal(generate(m, 'getMoveDropDown', new Move(0, 0, 0, 0, 0)).length, 0);
    m.infiniteLiquidDropdownDistance = true;
    assert(moveAt(generate(m, 'getMoveDropDown', new Move(0, 0, 0, 0, 0)), 1, -1, 0));
  },
  Adapter => {
    const f = waterFixture(Adapter, { height: .8 }); f.set(3, 0, 0, 'bedrock');
    assert(moveAt(generate(f.m, 'getMoveUp', swimNode()), 3, -1, 0));
    f.bot.entity.height = 1.8; assert.equal(generate(f.m, 'getMoveUp', swimNode()).length, 0);
    f.set(3, 0, 0, 'air'); f.bot.entity.height = 2.8; f.set(3, 1, 0, 'bedrock');
    assert.equal(generate(f.m, 'getMoveUp', swimNode()).length, 0);
    f.set(3, 1, 0, 'air'); f.bot.entity.height = 1.8; f.bot.entity.width = 1.4;
    assert.equal(generate(f.m, 'getMoveUp', swimNode()).length, 0);
    f.bot.entity.width = .6; const blockAt = f.bot.blockAt; f.bot.blockAt = p => p.y === 0 ? null : blockAt(p);
    assert.equal(generate(f.m, 'getMoveUp', swimNode()).length, 0);
  },
  Adapter => {
    const { m } = waterFixture(Adapter); m.allow1by1towers = true;
    assert.equal(generate(m, 'getMoveUp', swimNode(-1)).length, 0);
    assert.equal(generate(m, 'getMoveDown', swimNode(-2)).length, 0); // solid pool floor
  },
];

// Bundle exactly as a guest module: this rejects accidental Node built-ins in the implementation.
const pluginDependencies = createRequire(pathToFileURL(resolve(dependencies, '../../../bb-plugin/package.json')));
let build;
try { ({ build } = reference('esbuild')); }
catch { ({ build } = pluginDependencies('esbuild')); }
const bundled = await build({ entryPoints: [fileURLToPath(new URL('../../bb-plugin/scripting/movements.mjs', import.meta.url))],
  bundle: true, platform: 'browser', format: 'esm', target: 'es2022', write: false, nodePaths: [dependencies] });
const { Movements: Adapter } = await import(`data:text/javascript;base64,${Buffer.from(bundled.outputFiles[0].text).toString('base64')}`);
const defaultFixture = fixture();
const upstreamDefaults = new Upstream(defaultFixture.bot), adapterDefaults = new Adapter(defaultFixture.bot);
for (const key of Object.keys(upstreamDefaults)) assert.deepEqual(adapterDefaults[key], upstreamDefaults[key], `default ${key}`);
for (const key of Object.getOwnPropertyNames(Upstream.prototype)) assert.equal(typeof Adapter.prototype[key], 'function', key);
let passed = 0;
for (const [name, setup, configure = () => {}] of comparisons) {
  for (const nativeBody of [undefined, normal]) {
    const f = fixture({ nativeBody }); setup(f);
    const expected = new Upstream(f.bot); const actual = new Adapter(f.bot);
    configure(expected); configure(actual); expected.updateCollisionIndex(); actual.updateCollisionIndex();
    assert.deepEqual(normalize(actual.getNeighbors(origin())), normalize(expected.getNeighbors(origin())), `${name} (${nativeBody ? 'native' : 'default'})`);
    passed++;
  }
}
for (const [name, check] of scenarios) { check(Adapter); passed++; }
const waterFailures = [];
for (const [i, [name]] of waterContracts.entries()) {
  try { waterChecks[i](Adapter); passed++; }
  catch (error) { waterFailures.push({ name, error: error.message }); }
}
// Execute the same browser bundle in the actual guest engine, without Node globals.
const guestBundle = await build({ entryPoints: [fileURLToPath(new URL('../../bb-plugin/scripting/movements.mjs', import.meta.url))],
  bundle: true, platform: 'browser', format: 'iife', globalName: 'MovementModule', target: 'es2022', write: false, nodePaths: [dependencies] });
const { getQuickJS } = pluginDependencies('quickjs-emscripten');
const runtime = (await getQuickJS()).newRuntime(); runtime.setMemoryLimit(64 * 1024 * 1024);
const vm = runtime.newContext();
const guestRegistry = JSON.stringify({ blocksArray: registry.blocksArray, blocksByName: registry.blocksByName,
  itemsByName: registry.itemsByName, blockCollisionShapes: registry.blockCollisionShapes });
// Real Block observations are transferred into QuickJS; these no-dig probes need
// no substitute dig-time implementation. All movement generation remains bundled code.
const waterBlocks = {}, water = waterFixture(Adapter);
for (let x = -1; x <= 7; x++) for (let y = -4; y <= 4; y++) for (let z = -2; z <= 2; z++) {
  const b = water.bot.blockAt(new Vec3(x, y, z));
  waterBlocks[`${x},${y},${z}`] = { type: b.type, name: b.name, boundingBox: b.boundingBox, shapes: b.shapes };
}
const result = vm.evalCode(guestBundle.outputFiles[0].text + `
  const blocks = ${JSON.stringify(waterBlocks)};
  const movement = new MovementModule.Movements({ registry: ${guestRegistry}, nativeBody: ${JSON.stringify(normal)},
    entity: {width: 0.6, height: 1.8}, inventory: {items: () => []}, game: {minY: -16},
    blockAt(p) { const b = blocks[p.x + ',' + p.y + ',' + p.z]; return b ? {...b, position: p} : null; } });
  const defaults = { scaffolds: movement.countScaffoldingItems(), dig: movement.canDig, nodeGlobals: typeof process };
  movement.canDig = movement.allowParkour = movement.allow1by1towers = false;
  const entry = [], up = [], down = [];
  movement.getMoveDropDown({x: 0, y: 0, z: 0, remainingBlocks: 0}, {x: 1, z: 0}, entry);
  movement.getMoveUp({x: 3, y: -2, z: 0, remainingBlocks: 0}, up);
  movement.getMoveDown({x: 3, y: -1, z: 0, remainingBlocks: 0}, down);
  const summarize = list => list.map(n => [n.x, n.y, n.z, n.remainingBlocks, n.toBreak.length, n.toPlace.length]);
  JSON.stringify({ ...defaults, entry: summarize(entry), up: summarize(up), down: summarize(down) });
`);
try {
  if (result.error) throw new Error(JSON.stringify(vm.dump(result.error)));
  assert.deepEqual(JSON.parse(vm.getString(result.value)), { scaffolds: 0, dig: true, nodeGlobals: 'undefined',
    entry: [[1, -1, 0, 0, 0, 0]], up: [[3, -1, 0, 0, 0, 0]], down: [[3, -2, 0, 0, 0, 0]] });
} finally { result.error?.dispose(); result.value?.dispose(); vm.dispose(); runtime.dispose(); }
console.log(JSON.stringify({ status: waterFailures.length ? 'failed' : 'passed', scenarios: passed, differentialWorlds: comparisons.length,
  bodyScenarios: scenarios.length, quickjsInitialization: 'passed', browserBundleBytes: bundled.outputFiles[0].contents.length,
  quickjsWaterNeighbors: 'passed',
  waterCorrections: waterContracts, waterEvidence, waterFailures,
  limitation: 'Planner/source checks only; no native physics, execution, server or live conformance tested.' }, null, 2));
if (waterFailures.length) process.exitCode = 1;
