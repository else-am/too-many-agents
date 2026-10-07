// Preauthored upstream checks; no Minecraft/client or server startup.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const reference = createRequire(new URL('./package.json', import.meta.url));
const plugin = createRequire(new URL('../../bb-plugin/package.json', import.meta.url));
const registry = reference('minecraft-data')('1.21.1');
const Upstream = reference('mineflayer/lib/bossbar')(registry);
const titles = [{ type: 'string', value: 'Boss' }, { type: 'compound', value: { text: { type: 'string', value: 'Boss' }, bold: { type: 'byte', value: 1 } } }];
function inspect(Bar, title, color, dividers, flags) {
 const bar = new Bar('native-id', title, .625, dividers, color, flags);
 return { uuid: bar.entityUUID, title: bar.title.toString(), health: bar.health,
 color: bar.color, dividers: bar.dividers, dark: !!bar.shouldDarkenSky,
 dragon: !!bar.isDragonBar, fog: !!bar.shouldCreateFog };
}
const cases = [];
for (let flags=0; flags<8; flags++) for (let color=0; color<7; color++)
 cases.push([titles[flags%2], color, flags%5, flags]);
const expected = cases.map(args => inspect(Upstream,...args));
assert.notEqual(new Upstream('id',titles[0],1,0,0,6).flags,6,'upstream double-shift defect');
if(process.argv.includes('--reference-only')) console.log(`Reference: ${cases.length} cases; flag defect reproduced.`);
else {
 const { build }=plugin('esbuild'), { getQuickJS }=plugin('quickjs-emscripten');
 const root=fileURLToPath(new URL('../..',import.meta.url));
 const bundle=await build({stdin:{resolveDir:root,contents:`
 import { installBossBars } from './bb-plugin/scripting/boss-bars.mjs';
 import { createChatMessageClass } from './bb-plugin/scripting/chat.mjs';
 globalThis.bot={};globalThis.adapter=installBossBars(bot,createChatMessageClass({language:${JSON.stringify(registry.language)}}));
 globalThis.Bar=adapter.BossBar;`},bundle:true,write:false,platform:'browser',format:'iife',target:'es2022'});
 const vm=(await getQuickJS()).newContext();vm.runtime.setMemoryLimit(64*1024*1024);vm.runtime.setMaxStackSize(512*1024);
 let deadline;vm.runtime.setInterruptHandler(()=>Date.now()>deadline);
 function evaluate(code) {deadline=Date.now()+5000;const r=vm.evalCode(code),h=r.error??r.value;const value=vm.dump(h);h.dispose();if(r.error)throw Error(JSON.stringify(value));return value;}
 try {
 evaluate(bundle.outputFiles[0].text);evaluate(`globalThis.inspect=${inspect}`);
 cases.forEach((args,i)=>assert.deepEqual(evaluate(`inspect(Bar,...${JSON.stringify(args)})`),expected[i]));
 for(let f=0;f<8;f++)assert.equal(evaluate(`new Bar('id',{type:'string',value:'Boss'},1,0,0,${f}).flags`),f);
 assert.deepEqual(evaluate(`(()=>{
 const row={uuid:'one',title:{type:'string',value:'Boss'},health:1,dividers:0,color:0,flags:0};
 const initial=adapter.update([row],false), bar=bot.bossBars[0];
 const quiet=adapter.update([row],true), changed=adapter.update([{...row,health:.5}],true);
 const same=bot.bossBars[0]===bar, updated=bar.health;
 const removed=adapter.update([],true);
 return {initial:initial.length,quiet:quiet.length,changed:changed.map(e=>e[0]),same,updated,removed:removed.map(e=>e[0]),empty:bot.bossBars.length,old:removed[0][1]===bar};
 })()`),{initial:0,quiet:0,changed:['bossBarUpdated'],same:true,updated:.5,removed:['bossBarDeleted'],empty:0,old:true});
 console.log(`PASS ${cases.length} reference cases, eight flag corrections and snapshot identity/lifecycle in QuickJS.`);
 }finally{vm.dispose();}
}
