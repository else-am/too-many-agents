import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir, mkdtemp, symlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
const root='/Users/scott/Else/too-many-agents/scratch/worktrees/mineflayer-api';
const work='/Users/scott/.bb/plugins/environment-git-worktree/host-data/worktrees/thr_6qrtxjgv3m-1/too-many-agents';
const require=createRequire(root+'/bb-plugin/package.json');
const {build}=require('esbuild');
const host=await build({stdin:{contents:`export { minecraftScripts } from './scripts.ts'; export { minecraftWorlds } from './minecraft.ts'; export { ApiError } from './protocol.ts';`,resolveDir:work+'/bb-plugin'},bundle:true,platform:'node',format:'cjs',packages:'external',nodePaths:[root+'/bb-plugin/node_modules'],write:false,logLevel:'silent'});
const module={exports:{}};new Function('require','module','exports',host.outputFiles[0].text)(require,module,module.exports);
const {minecraftScripts,minecraftWorlds,ApiError}=module.exports;
const temp=await mkdtemp('/private/tmp/self-death-host-');
await mkdir(join(temp,'dist/scripting'),{recursive:true});
await symlink(root+'/bb-plugin/scripting',join(temp,'scripting'),'dir');
const guest=await build({stdin:{contents:`import {installState} from '${work}/bb-plugin/scripting/state.mjs'; import {EventEmitter} from 'events'; globalThis.MinecraftBot={createBot(initial){const bot=new EventEmitter();const state=installState(bot,{});state(initial);let ready;bot.ready=new Promise(r=>ready=r);bot.attempt=async()=>JSON.parse(await __mcRequest('action',JSON.stringify({type:'look'})));return {bot,update(frame){const events=state(frame);for(const e of events)bot.emit(...e);ready();}};}};`,resolveDir:root+'/bb-plugin'},bundle:true,platform:'browser',format:'iife',write:false});
await writeFile(join(temp,'dist/scripting/bot.js'),guest.outputFiles[0].text);
const initial=(health=20,alive=true,tick=1)=>({revision:tick,tick,action:{status:'idle'},body:{health,alive,airSupply:300,metadataWire:'/w==',metadataNonDefaults:[]},entities:[],
 worldState:{dayTime:String(tick),gameTime:String(tick),doDaylightCycle:true,isRaining:false,rainState:0,thunderState:0},
 itemRegistries:{items:['minecraft:air'],components:['minecraft:custom_data'],metadataSerializers:['minecraft:byte'],particles:['minecraft:smoke']},
 hands:{inventory:[],equipment:{},menu:{slots:[],carried:{wire:'AA==',count:0}}}});
const oldFetch=globalThis.fetch;const results=[];
try { for(const mode of ['known-start','known-await','known-heartbeat','unknown-start']) {
 const calls=[],trace=[];let stream,queued=false,terminalTimer,scriptId;
 const bb={pluginId:'fixture',server:{experimental_dataDir:'fixture'},onDispose(){},sdk:{plugins:{list:async()=>({plugins:[{id:'fixture',rootDir:temp}]})}}};
 const live={worldId:'fixture',worldSessionId:mode,callbackUrl:'http://127.0.0.1:1/fixture',callbackToken:'fixture-not-a-credential',connectionId:'fixture'};
 const json=(v,status=200)=>new Response(JSON.stringify(v),{status,headers:{'content-type':'application/json'}});
 const ok=result=>json({ok:true,result});
 const rejected=code=>json({ok:false,error:{code,message:'deliberately unrelated wording'}},409);
 const queueDeath=()=>{if(queued)return;queued=true;terminalTimer=setTimeout(()=>{trace.push('terminal-enqueued');stream.enqueue(new TextEncoder().encode(JSON.stringify({type:'state',snapshot:initial(0,false,3)})+'\n'+JSON.stringify({type:'error',message:'body_dead'})+'\n'));stream.close();},100);};
 globalThis.fetch=async(_url,options)=>{
  const packet=JSON.parse(options.body);
  if(packet.op==='cancel'){trace.push('scoped-cancel');return ok({});}
  const op=packet.arguments.operation;calls.push(op);
  if(op==='begin'){scriptId=packet.arguments.scriptId;return ok(initial());}
  assert.equal(packet.arguments.scriptId,scriptId);
  if(op==='stream') {
   const body=new ReadableStream({start(c){stream=c;c.enqueue(new TextEncoder().encode(JSON.stringify({type:'state',snapshot:initial(20,true,2)})+'\n'));},cancel(){clearTimeout(terminalTimer);trace.push('stream-cancelled');}});
   return new Response(body,{headers:{'content-type':'application/x-ndjson'}});
  }
  if(op==='end')return ok({});
  if(op==='heartbeat'){
   assert.equal(mode,'known-heartbeat');trace.push('known-heartbeat-rejected');queueDeath();return rejected('script_no_longer_controls_body');
  }
  if(op==='action'){
   assert.notEqual(mode,'known-heartbeat','a heartbeat gate must block subsequent dispatch');
   if(mode==='unknown-start'){trace.push('unknown-reply');queueDeath();throw new Error('lost reply');}
   if(mode==='known-start'){trace.push('known-start-rejected');queueDeath();return rejected('body_missing_or_unloaded');}
   return ok({id:'fixture-action',sequence:1,status:'running'});
  }
  if(op==='awaitAction'){assert.equal(mode,'known-await');trace.push('known-await-rejected');queueDeath();return rejected('script_no_longer_controls_body');}
  throw new Error('Unexpected operation '+op);
 };
 const worlds=minecraftWorlds(bb);
 // Exercise actual HTTP error decoding, not a structurally similar ApiError.
 const execute=minecraftScripts(bb,worlds);
 const report=await execute({timeoutMs:6000,code:`
  bot.on('health',(...args)=>console.log(JSON.stringify({event:'health',argc:args.length,health:bot.health,alive:bot.isAlive})));
  bot.on('death',(...args)=>console.log(JSON.stringify({event:'death',argc:args.length,health:bot.health,alive:bot.isAlive})));
  await bot.ready;
  ${mode==='known-heartbeat' ? 'await new Promise(r=>setTimeout(r,2050));' : ''}
  try { await bot.attempt(); } catch(e) { console.log('UNSAFE_CATCH'); await bot.attempt(); }
  await new Promise(()=>{});
 `},{threadId:'fixture-thread',signal:new AbortController().signal},live,'fixture-body');
 const body=JSON.parse(report.content[0].text);clearTimeout(terminalTimer);
 assert.equal(report.isError,true);assert.equal(body.bodyRelease,'confirmed');assert.equal(calls.filter(v=>v==='end').length,1);
 assert.ok(!body.execution.logs.includes('UNSAFE_CATCH'));
 if(mode==='unknown-start'){
  assert.equal(body.error,'Minecraft did not answer; its connection state is unknown.');
  assert.deepEqual(body.execution.logs,[]);assert.ok(!trace.includes('terminal-enqueued'));
 }else{
  assert.equal(body.error,'body_dead');
  assert.deepEqual(body.execution.logs.map(JSON.parse),[{event:'health',argc:0,health:0,alive:false},{event:'death',argc:0,health:0,alive:false}]);
 }
 assert.equal(calls.filter(v=>v==='action').length,mode==='known-heartbeat'?0:1);
 results.push({mode,passed:true,calls,trace,error:body.error,logs:body.execution.logs,updates:body.execution.updates,release:body.bodyRelease});
 }} finally {globalThis.fetch=oldFetch;}
await writeFile('/Users/scott/.bb/thread-storage/thr_6qrtxjgv3m/self-death-host-race-result.json',JSON.stringify(results,null,2)+'\n');
console.log(JSON.stringify(results));
