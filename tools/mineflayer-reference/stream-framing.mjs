// Preauthored stream-framing-scenarios.md: actual host parser, controlled byte source.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
const plugin=createRequire(new URL('../../bb-plugin/package.json',import.meta.url));
const root=fileURLToPath(new URL('../../bb-plugin',import.meta.url));
const bundled=await plugin('esbuild').build({stdin:{contents:"export {minecraftWorlds} from './minecraft.ts'",resolveDir:root},
  bundle:true,platform:'node',format:'cjs',packages:'external',write:false});
const module={exports:{}};new Function('require','module','exports',bundled.outputFiles[0].text)(plugin,module,module.exports);
const bb={pluginId:'framing-fixture',server:{experimental_dataDir:'framing-fixture'},onDispose(){}};
const live={worldId:'fixture',worldSessionId:'fixture',callbackUrl:'http://127.0.0.1:1/unused',callbackToken:'fixture-only',connectionId:'fixture'};
const worlds=module.exports.minecraftWorlds(bb),encoder=new TextEncoder(),limit=8*1024*1024;
const end=encoder.encode('{"type":"end"}\n');
const state=(text)=>JSON.stringify({type:'state',snapshot:{text}});
const sized=bytes=>encoder.encode(state('a'.repeat(bytes-encoder.encode(state('')).length))+'\n');
const oldFetch=globalThis.fetch,results=[];
async function run(name,chunks,expected,callback=async()=>{}){
  let calls=0,cancelled=0,index=0,closed=false;const delivered=[];
  globalThis.fetch=async()=>{calls++;return new Response(new ReadableStream({
    pull(controller){if(index<chunks.length)controller.enqueue(chunks[index++]);else {closed=true;controller.close();}},
    cancel(){cancelled++;}
  }),{headers:{'content-type':'application/x-ndjson'}});};
  let error=null;
  try {await worlds.callback(live,'stream',{},new AbortController().signal,async snapshot=>{
    delivered.push(snapshot.text);await callback(snapshot);
  });}catch(failure){error=failure;}
  assert.equal(calls,1,'no transport replay');
  if(expected.error)assert.match(error?.message??'',expected.error);else assert.equal(error,null);
  assert.deepEqual(delivered.map(value=>encoder.encode(value).length),expected.lengths);
  assert(cancelled===1||closed,'reader cancelled or source already closed');
  results.push({name,bytes:chunks.reduce((sum,value)=>sum+value.byteLength,0),deliveries:delivered.length,
    deliveredBytes:delivered.map(value=>encoder.encode(value).length),error:error?.message??null,calls,cancelled,closed});
  return delivered;
}
try {
  const half=5*1024*1024;
  await run('coalesced-valid-frames',[Buffer.concat([sized(half),sized(half),end])],
    {lengths:[half-encoder.encode(state('')).length,half-encoder.encode(state('')).length]});
  await run('exact-byte-limit',[sized(limit),end],{lengths:[limit-encoder.encode(state('')).length]});
  await run('one-byte-over',[sized(limit+1),end],{lengths:[],error:/exceeds 8 MiB/});
  const multibyte=encoder.encode(state('界'.repeat(3*1024*1024))+'\n');
  assert(multibyte.byteLength>limit);assert(new TextDecoder().decode(multibyte).length<limit);
  await run('multibyte-over',[multibyte,end],{lengths:[],error:/exceeds 8 MiB/});
  const text='雪😀',prefix=encoder.encode('{"type":"state","snapshot":{"text":"'),tail=encoder.encode('"}}\n');
  const unicode=encoder.encode(text);
  const delivered=await run('split-utf8',[encoder.encode('\n'),prefix,...Array.from(unicode,b=>new Uint8Array([b])),tail,end],{lengths:[7]});
  assert.equal(delivered[0],text);
  await run('invalid-utf8',[prefix,new Uint8Array([0xff]),tail,end],{lengths:[],error:/encoded data|encoding/i});
  let acknowledge,entered;const gate=new Promise(resolve=>acknowledge=resolve),started=new Promise(resolve=>entered=resolve);
  const events=[];
  const pending=run('ordered-error',[encoder.encode(state('first')+'\n'+state('second')+'\n'+JSON.stringify({type:'error',message:'body_dead'})+'\n')],
    {lengths:[5,6],error:/body_dead/},async snapshot=>{events.push(snapshot.text);if(snapshot.text==='first'){entered();await gate;}});
  await started;await new Promise(resolve=>setImmediate(resolve));assert.deepEqual(events,['first']);acknowledge();await pending;
  assert.deepEqual(events,['first','second']);
  await run('partial-eof',[prefix],{lengths:[],error:/without confirmation/,cancelled:false});
  await run('missing-terminal',[encoder.encode(state('observed')+'\n')],{lengths:[8],error:/without confirmation/,cancelled:false});
  await run('ignore-after-end',[Buffer.concat([end,sized(100)])],{lengths:[]});
}finally{globalThis.fetch=oldFetch;}
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const report={result:'passed',cases:results,sourceSha256:sha(await readFile(new URL(import.meta.url))),
  hostSha256:sha(await readFile(new URL('../../bb-plugin/minecraft.ts',import.meta.url))),bundleSha256:sha(bundled.outputFiles[0].contents),
  limitation:'Actual host parser with controlled Response bytes; no native game, request mutations or snapshot construction/runner coverage.'};
await writeFile(new URL('../../run/mineflayer-reference/stream-framing.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({result:report.result,cases:results.length,limitation:report.limitation}));
