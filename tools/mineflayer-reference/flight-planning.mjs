// Authored before the flying-mode planner changes. No native world calls.
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
const caps = { physics:'native-parrot-flight-post-tick', locomotion:'flying', canFly:true,
  flightTargetYOffset:.05, canSwim:false, canJump:false, stepHeight:0,
  jumpHeight:0, maxJumpDistance:0, maxSprintJumpDistance:0 };
const start=()=>new Move(0,0,0,0,0);
function fixture(){
  const cells=new Map();
  const bot={registry,game:{minY:-8},nativeBody:{...caps},entities:{},
    entity:{width:.5,height:.9,eyeHeight:.54,position:new Vec3(.5,.05,.5),effects:{}},
    inventory:{items:()=>[]},pathfinder:{bestHarvestTool:()=>null},
    blockAt(p){p=p.floored();if(Math.abs(p.x)>4||Math.abs(p.z)>4||p.y< -3||p.y>4)return null;
      const spec=cells.get(p.toString())??{name:'air'};
      const b=Block.fromProperties(spec.name,spec.properties??{},0);b.position=p;return b;}};
  const set=(x,y,z,name,properties)=>cells.set(new Vec3(x,y,z).toString(),{name,properties});
  const m=new Movements(bot);m.canDig=false;m.allowParkour=false;m.allow1by1towers=false;
  return {bot,m,set};
}
const target=(moves,x,y,z)=>moves.some(n=>n.x===x&&n.y===y&&n.z===z);
const checks=[
 ['three-dimensional flight without a support floor',()=>{
   const {m}=fixture();for(const y of [-1,1]){
     const r=new AStar(start(),m,new GoalBlock(2,y,0),1000,1000).compute();
     assert.equal(r.status,'success');assert(r.path.every(n=>!n.toBreak.length&&!n.toPlace.length&&!n.parkour));
   }
 }],
 ['offset and actual height reject a low roof',()=>{
   const {m,bot,set}=fixture();bot.entity.height=1;bot.nativeBody.flightTargetYOffset=.2;set(1,1,0,'stone');
   assert.equal(target(m.getNeighbors(start()),1,0,0),false);
 }],
 ['diagonal sweep rejects a solid corner',()=>{
   const {m,set}=fixture();set(1,0,0,'stone');assert.equal(target(m.getNeighbors(start()),1,0,1),false);
 }],
 ['ordinary flight cannot enter fluid',()=>{
   for(const name of ['water','lava','bubble_column']){
     const {m,set}=fixture();set(1,0,0,name);assert.equal(target(m.getNeighbors(start()),1,0,0),false,name);
   }
 }],
 ['unknown and excluded cells remain unavailable',()=>{
   const {m,bot}=fixture();m.exclusionAreasStep.push(b=>b.position?.x===1?200:0);
   assert.equal(target(m.getNeighbors(start()),1,0,0),false);m.exclusionAreasStep.length=0;
   const get=bot.blockAt;bot.blockAt=p=>p.floored().equals(new Vec3(1,0,0))?null:get(p);
   assert.equal(target(m.getNeighbors(start()),1,0,0),false);
 }],
 ['capability and finite offset are required',()=>{
   for(const patch of [{canFly:false},{flightTargetYOffset:NaN}]){
     const {m,bot}=fixture();Object.assign(bot.nativeBody,patch);assert.equal(m.getNeighbors(start()).length,0);
   }
 }],
];
const results=[];for(const [name,check]of checks){try{check();results.push({name,status:'passed'});}catch(error){results.push({name,status:'failed',error:error.message});}}
console.log(JSON.stringify({results,limitation:'Real Block/AStar and bundled guest graph only; native flight remains unimplemented.'},null,2));
if(results.some(r=>r.status==='failed'))process.exitCode=1;
