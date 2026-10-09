// Preauthored host/real-QuickJS fault scenario: uncertain HTTP errors must not reach guest catch/retry.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdir, mkdtemp, symlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root=fileURLToPath(new URL('../../', import.meta.url));
const require=createRequire(root+'/bb-plugin/package.json');
const {build}=require('esbuild');
const host=await build({stdin:{contents:`export { minecraftScripts } from './scripts.ts'; export { minecraftWorlds } from './minecraft.ts';`,resolveDir:root+'/bb-plugin'},bundle:true,platform:'node',format:'cjs',packages:'external',nodePaths:[root+'/bb-plugin/node_modules'],write:false,logLevel:'silent'});
const module={exports:{}};new Function('require','module','exports',host.outputFiles[0].text)(require,module,module.exports);
const {minecraftScripts,minecraftWorlds}=module.exports;
const temp=await mkdtemp('/private/tmp/self-death-host-');
await mkdir(join(temp,'dist/scripting'),{recursive:true});
await symlink(root+'/bb-plugin/scripting',join(temp,'scripting'),'dir');
const guest=await build({stdin:{contents:`import {installState} from '${root}/bb-plugin/scripting/state.mjs'; import {EventEmitter} from 'events'; globalThis.MinecraftBot={createBot(initial){const bot=new EventEmitter();const state=installState(bot);state(initial);let ready;bot.ready=new Promise(r=>ready=r);bot.attempt=async()=>JSON.parse(await __mcRequest('action',JSON.stringify({type:'look'})));return {bot,update(frame){const events=state(frame);for(const e of events)bot.emit(...e);ready();}};}};`,resolveDir:root+'/bb-plugin'},bundle:true,platform:'browser',format:'iife',write:false});
await writeFile(join(temp,'dist/scripting/bot.js'),guest.outputFiles[0].text);
const initial=(health=20,alive=true,tick=1)=>({revision:tick,tick,action:{status:'idle'},body:{health,alive,airSupply:300,metadataWire:'/w==',metadataNonDefaults:[]},entities:[],
 worldState:{dayTime:String(tick),gameTime:String(tick),doDaylightCycle:true,isRaining:false,rainState:0,thunderState:0},
 itemRegistries:{items:['minecraft:air'],components:['minecraft:custom_data'],metadataSerializers:['minecraft:byte'],particles:['minecraft:smoke']},
 hands:{inventory:[],equipment:{},menu:{slots:[],carried:{wire:'AA==',count:0}}}});
const oldFetch=globalThis.fetch;const results=[];
try { for(const mode of ['timeout-response','interrupted-response','unstructured-response','generic-failure','known-rejection']) {
 const calls=[],trace=[];let stream,queued=false,terminalTimer,scriptId;
 const bb={pluginId:'fixture',server:{experimental_dataDir:'fixture'},onDispose(){},sdk:{plugins:{list:async()=>({plugins:[{id:'fixture',rootDir:temp}]})}}};
 const live={worldId:'fixture',worldSessionId:mode,callbackUrl:'http://127.0.0.1:1/fixture',callbackToken:'fixture-not-a-credential',connectionId:'fixture'};
 const json=(v,status=200)=>new Response(JSON.stringify(v),{status,headers:{'content-type':'application/json'}});
 const ok=result=>json({ok:true,result});
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
  if(op==='action'){
   if(mode==='known-rejection') {
    if(calls.filter(value=>value==='action').length===1)
     return json({ok:false,error:{code:'action_rejected_before_start',message:'invalid fixture request'}},400);
    return ok({id:'fixture-action',sequence:1,status:'running'});
   }
   queueDeath();
   if(mode==='timeout-response') return json({ok:false,error:{message:'callback_outcome_unknown_do_not_retry'}},504);
   if(mode==='interrupted-response') return json({error:'interrupted_outcome_unknown'},503);
   if(mode==='unstructured-response') return json({ok:false},502);
   return json({ok:false,error:{code:'callback_failed',message:'unexpected native failure'}},400);
  }
  if(op==='awaitAction') {
   assert.equal(mode,'known-rejection');
   return ok({id:'fixture-action',sequence:2,status:'completed'});
  }
  throw new Error('Unexpected operation '+op);
 };
 const worlds=minecraftWorlds(bb);
 // Exercise actual HTTP error decoding, not a structurally similar ApiError.
 const execute=minecraftScripts(bb,worlds);
 const report=await execute({timeoutMs:6000,code:`
  bot.on('health',(...args)=>console.log(JSON.stringify({event:'health',argc:args.length,health:bot.health,alive:bot.isAlive})));
  bot.on('death',(...args)=>console.log(JSON.stringify({event:'death',argc:args.length,health:bot.health,alive:bot.isAlive})));
  await bot.ready;
  try { await bot.attempt(); } catch(e) { console.log('${mode==='known-rejection'?'KNOWN_REJECTION':'UNSAFE_CATCH'}'); await bot.attempt(); return 'recovered'; }
  await new Promise(()=>{});
 `},{threadId:'fixture-thread',signal:new AbortController().signal},live,'fixture-body');
 const body=JSON.parse(report.content[0].text);clearTimeout(terminalTimer);
 assert.equal(body.bodyRelease,'confirmed');assert.equal(calls.filter(v=>v==='end').length,1);
 if(mode==='known-rejection') {
  assert.notEqual(report.isError,true);
  assert.equal(body.result.value,'recovered');
  assert.deepEqual(body.result.logs,['KNOWN_REJECTION']);
  assert.equal(calls.filter(v=>v==='action').length,2);
 } else {
  assert.equal(report.isError,true);
  assert.match(body.error,/callback_outcome_unknown_do_not_retry|Minecraft returned HTTP (503|502)|unexpected native failure/);
  assert.deepEqual(body.execution.logs,[]);assert.ok(!trace.includes('terminal-enqueued'));
  assert.equal(calls.filter(v=>v==='action').length,1);
 }
 results.push({mode,passed:true,calls,trace,error:body.error,logs:(body.execution??body.result).logs,release:body.bodyRelease});
 }} finally {globalThis.fetch=oldFetch;}
await mkdir(join(root,'run/mineflayer-reference'),{recursive:true});
await writeFile(join(root,'run/mineflayer-reference/action-start-http-uncertainty.json'),JSON.stringify(results,null,2)+'\n');
console.log(JSON.stringify(results));
