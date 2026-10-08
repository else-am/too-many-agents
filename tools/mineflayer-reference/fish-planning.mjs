// Authored before the submerged-mode planner changes. No native world calls.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const reference = createRequire(new URL('./package.json', import.meta.url));
const plugin = createRequire(new URL('../../bb-plugin/package.json', import.meta.url));
const { Vec3 } = reference('vec3');
const registry = reference('prismarine-registry')('1.21.1');
const Block = reference('prismarine-block')(registry);
const Move = reference('mineflayer-pathfinder/lib/move');
const AStar = reference('mineflayer-pathfinder/lib/astar');
const { GoalBlock } = reference('mineflayer-pathfinder/lib/goals');
assert.equal(reference('mineflayer-pathfinder/package.json').version, '2.4.5');
const { build } = plugin('esbuild');
const output = await build({
  entryPoints: [fileURLToPath(new URL('../../bb-plugin/scripting/movements.mjs', import.meta.url))],
  bundle: true, platform: 'browser', format: 'esm', write: false,
  nodePaths: [fileURLToPath(new URL('./node_modules', import.meta.url))],
});
const { Movements } = await import(`data:text/javascript;base64,${Buffer.from(output.outputFiles[0].text).toString('base64')}`);
const caps = { physics: 'native-fish-submerged-post-tick', locomotion: 'submerged',
  swimTargetYOffset: .35, canSwim: true, canJump: false, stepHeight: 0,
  jumpHeight: 0, maxJumpDistance: 0, maxSprintJumpDistance: 0 };
const start = () => new Move(0, 0, 0, 0, 0);
function fixture() {
  const cells = new Map();
  const bot = { registry, game: { minY: -8 }, nativeBody: { ...caps }, entities: {},
    entity: { width: .5, height: .3, position: new Vec3(.5, .35, .5), effects: {} },
    inventory: { items: () => [] }, pathfinder: { bestHarvestTool: () => null },
    blockAt(p) {
      p = p.floored();
      if (Math.abs(p.x) > 4 || Math.abs(p.z) > 4 || p.y < -3 || p.y > 4) return null;
      const spec = cells.get(p.toString()) ?? { name: 'water', properties: { level: 0 } };
      const b = Block.fromProperties(spec.name, spec.properties ?? {}, 0); b.position = p; return b;
    } };
  const set = (x,y,z,name,properties) => cells.set(new Vec3(x,y,z).toString(), {name,properties});
  const m = new Movements(bot); m.canDig = false; m.allowParkour = false; m.allow1by1towers = false;
  return {bot,m,set};
}
const target = (moves,x,y,z) => moves.some(n=>n.x===x && n.y===y && n.z===z);
const checks = [
  ['submerged ascent and descent without support', () => {
    const {m} = fixture();
    for (const y of [-1,1]) {
      const r = new AStar(start(),m,new GoalBlock(2,y,0),1000,1000).compute();
      assert.equal(r.status,'success');
      assert(r.path.every(n=>!n.toBreak.length && !n.toPlace.length && !n.parkour));
    }
  }],
  ['dry bank is unavailable even with solid support', () => {
    const {m,set} = fixture(); set(1,0,0,'air'); set(1,-1,0,'stone');
    assert.equal(target(m.getNeighbors(start()),1,0,0),false);
    const direct=[]; m.getMoveForward(start(),{x:1,z:0},direct);
    assert.equal(direct.length,0);
  }],
  ['special liquids are not ordinary submerged water', () => {
    for (const name of ['lava','bubble_column']) {
      const {m,set}=fixture(); set(1,0,0,name);
      assert.equal(target(m.getNeighbors(start()),1,0,0),false,name);
    }
  }],
  ['target offset contributes to roof clearance', () => {
    const {m,bot,set}=fixture(); bot.entity.height=.8; bot.nativeBody.swimTargetYOffset=.35;
    set(1,1,0,'stone');
    assert.equal(target(m.getNeighbors(start()),1,0,0),false);
  }],
  ['low flowing surface cannot cover the body', () => {
    const {m,set}=fixture(); set(1,0,0,'water',{level:7}); set(1,1,0,'air');
    assert.equal(target(m.getNeighbors(start()),1,0,0),false);
  }],
  ['policies and unknown cells remain authoritative', () => {
    const {m,bot}=fixture(); m.exclusionAreasStep.push(b=>b.position?.x===1?200:0);
    assert.equal(target(m.getNeighbors(start()),1,0,0),false);
    m.exclusionAreasStep.length=0; const lookup=bot.blockAt;
    bot.blockAt=p=>p.floored().equals(new Vec3(1,0,0))?null:lookup(p);
    assert.equal(target(m.getNeighbors(start()),1,0,0),false);
  }],
];
const results=[];
for(const [name,check] of checks) {
  try { check(); results.push({name,status:'passed'}); }
  catch(error) { results.push({name,status:'failed',error:error.message}); }
}
console.log(JSON.stringify({results,limitation:'Real Block/AStar and browser-bundled planner only; no native travel or controller validation.'},null,2));
if(results.some(r=>r.status==='failed'))process.exitCode=1;
