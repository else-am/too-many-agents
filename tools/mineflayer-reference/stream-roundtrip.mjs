// Feed actual ScriptStream probe output through the actual host callback parser.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
if(!process.argv[2])throw new Error('Pass the actual ScriptStream oversize-evidence.ndjson artifact');
const bytes=await readFile(process.argv[2]);
const plugin=createRequire(new URL('../../bb-plugin/package.json',import.meta.url));
const bundle=await plugin('esbuild').build({stdin:{contents:"export {minecraftWorlds} from './minecraft.ts'",resolveDir:fileURLToPath(new URL('../../bb-plugin',import.meta.url))},
  bundle:true,platform:'node',format:'cjs',packages:'external',write:false});
const module={exports:{}};new Function('require','module','exports',bundle.outputFiles[0].text)(plugin,module,module.exports);
const bb={pluginId:'roundtrip-fixture',server:{experimental_dataDir:'roundtrip-fixture'},onDispose(){}};
const live={worldId:'fixture',worldSessionId:'fixture',callbackUrl:'http://127.0.0.1:1/unused',callbackToken:'fixture-only',connectionId:'fixture'};
const worlds=module.exports.minecraftWorlds(bb),oldFetch=globalThis.fetch;
let requests=0,offset=0;const revisions=[];
try{
  globalThis.fetch=async()=>{requests++;return new Response(new ReadableStream({pull(controller){
    if(offset===bytes.length){controller.close();return;}
    const end=Math.min(offset+7,bytes.length);controller.enqueue(bytes.subarray(offset,end));offset=end;
  }}),{headers:{'content-type':'application/x-ndjson'}});};
  await assert.rejects(worlds.callback(live,'stream',{},new AbortController().signal,async snapshot=>{
    await new Promise(resolve=>setImmediate(resolve));revisions.push(snapshot.revision);
  }),/^Error: script_state_frame_too_large$/);
  assert.deepEqual(revisions,[1,2]);assert.equal(requests,1);
}finally{globalThis.fetch=oldFetch;}
const sha=data=>createHash('sha256').update(data).digest('hex');
const report={result:'passed',revisions,requests,error:'script_state_frame_too_large',wireBytes:bytes.length,
  wireSha256:sha(bytes),sourceSha256:sha(await readFile(new URL(import.meta.url))),
  hostSha256:sha(await readFile(new URL('../../bb-plugin/minecraft.ts',import.meta.url))),
  producerSha256:sha(await readFile(new URL('../../src/main/java/toomanyagents/ScriptStream.java',import.meta.url))),
  limitation:'Actual Java-probe wire to actual host parser; no Minecraft world, runner hydration or game-death lifecycle evidence.'};
await writeFile(new URL('../../run/mineflayer-reference/stream-roundtrip.json',import.meta.url),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report));
