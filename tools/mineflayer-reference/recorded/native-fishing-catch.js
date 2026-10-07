let phase='baseline';const out={};
try{
 const rod=bot.inventory.slots.find(i=>i?.name==='fishing_rod');if(!rod)throw new Error('Rod missing');
 await bot.equip(rod,'hand');
 const inventory=()=>bot.inventory.slots.map(i=>i?{slot:i.slot,name:i.name,count:i.count,components:i.components}:null);
 out.before={inventory:inventory(),xp:bot.experience.points,rodDamage:rod.durabilityUsed,position:bot.entity.position.clone(),age:String(bot.time.bigAge)};
 const spawned=[],gone=[];bot.on('entitySpawn',e=>{if(e.name==='fishing_bobber')spawned.push({id:e.id,uuid:e.uuid});});bot.on('entityGone',e=>{if(e.name==='fishing_bobber')gone.push({id:e.id,uuid:e.uuid});});
 phase='aim';await bot.lookAt(new Vec3(52.5,-59.12,12.5),true);
 phase='natural catch';out.outcome=await bot.fish();out.retrieved=true;out.hooks={spawned,gone};
 phase='post catch ticks';const age=bot.time.bigAge;let ticks=0;const tick=()=>ticks++;bot.on('physicsTick',tick);
 await bot.waitForTicks(20);bot.removeListener('physicsTick',tick);
 out.tickAdvance={ticks,ageBefore:String(age),ageAfter:String(bot.time.bigAge)};
 if(ticks<20||bot.time.bigAge-age<20n)throw new Error('Post-catch tick advance unhealthy');
 const afterRod=bot.inventory.slots.find(i=>i?.name==='fishing_rod');
 out.after={inventory:inventory(),xp:bot.experience.points,rodDamage:afterRod?.durabilityUsed,rodComponents:afterRod?.components};
 const totals=items=>{const result={};for(const i of items.filter(Boolean))result[i.name]=(result[i.name]||0)+i.count;return result;};
 const beforeTotals=totals(out.before.inventory),afterTotals=totals(out.after.inventory);
 out.inventoryDelta=Object.fromEntries([...new Set([...Object.keys(beforeTotals),...Object.keys(afterTotals)])].map(name=>[name,(afterTotals[name]||0)-(beforeTotals[name]||0)]).filter(([,delta])=>delta));
 out.entities=Object.values(bot.entities).filter(e=>['item','experience_orb','fishing_bobber'].includes(e.name)).map(e=>({id:e.id,uuid:e.uuid,name:e.name,position:e.position,droppedItem:e.getDroppedItem?.(),metadata:e.metadata}));
 out.hooks={spawned,gone,remaining:Object.values(bot.entities).filter(e=>e.name==='fishing_bobber').map(e=>({id:e.id,uuid:e.uuid}))};return out;
}catch(e){console.log('Failure phase: '+phase);console.log('Evidence: '+JSON.stringify(out));throw e;}
