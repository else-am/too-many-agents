let phase='observe';const out={};const safe=v=>JSON.parse(JSON.stringify(v,(_,x)=>typeof x==='bigint'?String(x):x));
try{
 const foot=bot.blockAt(new Vec3(32,-60,-5)),head=bot.blockAt(new Vec3(32,-60,-4));
 out.input={bodyUUID:bot.entity.uuid,position:bot.entity.position.clone(),vehicle:bot.vehicle?.uuid??null,gamemode:bot.game.gameMode,time:safe(bot.time),foot:{name:foot?.name,properties:foot?.getProperties()},head:{name:head?.name,properties:head?.getProperties()},inventory:bot.inventory.slots.map(i=>i?{slot:i.slot,name:i.name,count:i.count,components:i.components}:null)};
 if(bot.vehicle||bot.entity.position.distanceTo(new Vec3(32.5,-60,-6.5))>1||bot.entity.position.distanceTo(foot.position.offset(.5,.5,.5))>4.5)throw new Error('Body fixture/reach precondition differs');
 if(!bot.isABed(foot)||!bot.isABed(head))throw new Error('Bed recognition failed');
 out.input.foot.parsed=bot.parseBedMetadata(foot);out.input.head.parsed=bot.parseBedMetadata(head);
 const fp=foot.getProperties(),hp=head.getProperties();
 if(fp.occupied||hp.occupied||fp.part!=='foot'||hp.part!=='head'||fp.facing!=='south'||hp.facing!=='south'||bot.game.gameMode!=='survival')throw new Error('Bed/survival prerequisites differ');
 if(bot.time.timeOfDay<12541||bot.time.timeOfDay>23458)throw new Error('Not night');
 console.log('Input: '+JSON.stringify(out.input));
 const events=[];bot.on('sleep',()=>events.push({event:'sleep',isSleeping:bot.isSleeping,entitySleeping:bot.entity.isSleeping}));
 bot.on('entitySleep',e=>events.push({event:'entitySleep',id:e.id,uuid:e.uuid,isSleeping:bot.isSleeping,entitySleeping:bot.entity.isSleeping}));
 phase='sleep';await bot.sleep(foot);
 if(!bot.isSleeping||!bot.entity.isSleeping)throw new Error('Sleep fields not updated');
 if(!events.some(e=>e.event==='sleep')||!events.some(e=>e.event==='entitySleep'))throw new Error('Sleep events missing');
 phase='remain asleep';await bot.waitForTicks(3);
 if(!bot.isSleeping||!bot.entity.isSleeping)throw new Error('Sleep did not persist');
 out.sleep={isSleeping:bot.isSleeping,entitySleeping:bot.entity.isSleeping,position:bot.entity.position.clone(),events,footProperties:bot.blockAt(foot.position).getProperties(),headProperties:bot.blockAt(head.position).getProperties()};return out;
}catch(e){console.log('Failure phase: '+phase);console.log('Evidence: '+JSON.stringify(safe(out)));throw e;}
