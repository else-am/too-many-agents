// Pinned local Goal contracts in Node and browser-bundled QuickJS; no native route.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname } from 'node:path';
const reference = createRequire(new URL('./package.json', import.meta.url));
const plugin = createRequire(new URL('../../bb-plugin/package.json', import.meta.url));
const moduleName = 'mineflayer-pathfinder/lib/goals.js';
assert.equal(reference('mineflayer-pathfinder/package.json').version, '2.4.5');
assert.equal(plugin('mineflayer-pathfinder/package.json').version, '2.4.5');
const classes = ['Goal', 'GoalBlock', 'GoalNear', 'GoalXZ', 'GoalNearXZ', 'GoalY',
  'GoalGetToBlock', 'GoalCompositeAny', 'GoalCompositeAll', 'GoalInvert'];
// This evidence applies to the unchanged library exports, not the corrected wrapper.
const botSource = await readFile(new URL('../../bb-plugin/scripting/bot.mjs', import.meta.url), 'utf8');
assert.match(botSource, /import upstreamGoals from 'mineflayer-pathfinder\/lib\/goals.js'/);
assert.match(botSource, /const goals = \{ \.\.\.upstreamGoals,/);
for (const name of classes) assert(!new RegExp(`\\b${name}:`).test(botSource));
function exercise(g) {
  const rows = [], fields = goal => Object.fromEntries(['x','y','z','rangeSq']
    .filter(key => key in goal).map(key => [key,goal[key]]));
  const nodes = [{x:-2,y:2,z:3},{x:-1,y:2,z:3},{x:0,y:2,z:3},
    {x:-2,y:8,z:3},{x:-2,y:1,z:3},{x:-1,y:1,z:3},{x:4,y:-5,z:-6}];
  const constructors = [['Goal',[]],['GoalBlock',[-1.2,2.9,3.1]],['GoalNear',[-1.2,2.9,3.1,2]],
    ['GoalXZ',[-1.2,3.1]],['GoalNearXZ',[-1.2,3.1,2]],['GoalY',[2.9]],
    ['GoalGetToBlock',[-1.2,2.9,3.1]]];
  for (const [name,args] of constructors) {
    const goal = new g[name](...args);
    const evaluate = () => nodes.map(node => ({end:goal.isEnd(node),h:goal.heuristic(node)}));
    const before = {fields:fields(goal),nodes:evaluate(),changed:goal.hasChanged(),valid:goal.isValid()};
    for (const key of Object.keys(fields(goal))) goal[key] += 1;
    rows.push({name,typed:goal instanceof g.Goal,before,after:{fields:fields(goal),nodes:evaluate()}});
  }
  for (const name of ['GoalCompositeAny','GoalCompositeAll']) {
    const empty = new g[name](), list = [], goal = new g[name](list);
    const state = {changed:false,valid:true};
    class Mutable extends g.Goal {
      heuristic(node) {return node.x+3;}
      isEnd(node) {return node.x===1;}
      hasChanged() {return state.changed;}
      isValid() {return state.valid;}
    }
    const child = new Mutable(), block = new g.GoalBlock(2,0,0), node = {x:1,y:0,z:0};
    const emptyValues = [empty.isEnd(node),empty.heuristic(node),empty.hasChanged(),empty.isValid()];
    const pushReturnsUndefined = goal.push(child) === undefined;
    goal.push(block);
    const before = [goal.isEnd(node),goal.heuristic(node),goal.hasChanged(),goal.isValid()];
    state.changed=true;state.valid=false;
    rows.push({name,typed:goal instanceof g.Goal,listIdentity:goal.goals===list,
      childIdentity:goal.goals[0]===child,length:list.length,pushReturnsUndefined,emptyValues,before,
      after:[goal.hasChanged(),goal.isValid()]});
  }
  const child = new g.GoalBlock(1,0,0), inverse = new g.GoalInvert(child);
  rows.push({name:'GoalInvert',typed:inverse instanceof g.Goal,childIdentity:inverse.goal===child,
    nodes:[{x:1,y:0,z:0},{x:2,y:0,z:0}].map(node=>[inverse.isEnd(node),inverse.heuristic(node)]),
    before:[inverse.hasChanged(),inverse.isValid()]});
  child.hasChanged=()=>true;child.isValid=()=>false;
  rows.at(-1).after=[inverse.hasChanged(),inverse.isValid()];
  return rows;
}
const expected=exercise(reference(moduleName));
assert.deepEqual(expected[1].before.fields,{x:-2,y:2,z:3});
assert.deepEqual(expected[1].before.nodes.slice(0,3),[{end:true,h:0},{end:false,h:1},{end:false,h:2}]);
assert.equal(expected[2].before.nodes[2].end,true); // Exact radius boundary.
assert.equal(expected[3].before.nodes[3].end,true); // XZ ignores Y.
assert.equal(expected[2].before.nodes[3].end,false); // 3D range includes Y.
assert.equal(expected[5].before.nodes[0].end,true);
assert.equal(expected[6].before.nodes[0].end,false); // Occupied target is not adjacent.
assert.equal(expected[6].before.nodes[1].end,true);
assert.equal(expected[6].before.nodes[5].end,true); // Lower-side stance in the pin.
assert.deepEqual(expected[7].before,[true,1,false,true]);
assert.deepEqual(expected[8].before,[false,4,false,true]);
assert.deepEqual(expected[9].nodes,[[false,-0],[true,-1]]);
const {build}=plugin('esbuild');
const bundle=await build({stdin:{resolveDir:dirname(plugin.resolve(moduleName)),contents:`import goals from ${JSON.stringify(plugin.resolve(moduleName))};globalThis.goals=goals;`},
  bundle:true,write:false,platform:'browser',format:'iife',metafile:true});
assert(Object.values(bundle.metafile.outputs).every(o=>o.imports.length===0));
const vm=(await plugin('quickjs-emscripten').getQuickJS()).newContext();
vm.runtime.setMemoryLimit(64*1024*1024);vm.runtime.setMaxStackSize(512*1024);
const deadline=Date.now()+10000;vm.runtime.setInterruptHandler(()=>Date.now()>deadline);
function evaluate(code){const result=vm.evalCode(code),handle=result.error??result.value;
  const value=vm.dump(handle);handle.dispose();if(result.error)throw new Error(JSON.stringify(value));return value;}
let actual;
try {evaluate(bundle.outputFiles[0].text);actual=JSON.parse(evaluate(`JSON.stringify((${exercise})(goals))`));
  assert.deepEqual(actual,JSON.parse(JSON.stringify(expected)));}
finally{vm.dispose();}
const sha=data=>createHash('sha256').update(data).digest('hex');
const report={result:'passed',classes,rows:actual,version:'2.4.5',memoryBytes:64*1024*1024,stackBytes:512*1024,
  sourceSha256:sha(await readFile(new URL(import.meta.url))),referenceSha256:sha(await readFile(reference.resolve(moduleName))),
  guestSha256:sha(await readFile(plugin.resolve(moduleName))),botSourceSha256:sha(botSource),bundleSha256:sha(bundle.outputFiles[0].contents),
  limitations:'Local coordinate/composite predicate portability only; excludes world/entity hydration, corrected GoalBreakBlock, route consumption and native movement/cancellation.'};
await writeFile(new URL('../../run/mineflayer-reference/goal-library.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({result:report.result,classes,rows:actual.length,limitations:report.limitations}));
