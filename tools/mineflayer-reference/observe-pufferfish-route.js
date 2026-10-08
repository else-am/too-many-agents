// Prepared only. Each phase needs a distinct NEW body; see pufferfish-route-fixture.md.
const selectedPhase = 'ascent'; // Explicit separate invocation: 'puff-change'.
const expectedUuid = 'REPLACE_WITH_NEW_PUFFERFISH_UUID';
if (!['ascent','puff-change'].includes(selectedPhase)) throw new Error('Unknown prepared Pufferfish phase');
const body=bot.entity, caps=bot.nativeBody, origin=new Vec3(310.5,-60,110.5);
// Generated 1.21.1 hierarchy: Entity 0..7, LivingEntity 8..14, Mob 15, AbstractFish 16, puff 17.
const puffIndex=17, metadata=body.metadata;
const initialPuff={present:Object.prototype.hasOwnProperty.call(metadata,puffIndex),value:metadata[puffIndex]??null};
if (body.uuid!==expectedUuid || body.name!=='pufferfish' || !body.alive || !body.onGround ||
    body.position.distanceTo(origin)>.2 || !body.isInWater || body.isInLava || body.isSleeping || bot.vehicle ||
    bot.inventory.slots.some(Boolean) || bot.currentWindow || bot.inventory.selectedItem ||
    Object.values(bot.controlState).some(Boolean) || (initialPuff.present&&initialPuff.value!==0))
  throw new Error('Fresh empty settled puff-zero Pufferfish fixture differs');
if (caps?.physics!=='native-fish-submerged-post-tick' || caps.locomotion!=='submerged' ||
    caps.canSwim!==true || caps.canJump!==false || caps.jumpHeight!==0 ||
    caps.maxJumpDistance!==0 || caps.maxSprintJumpDistance!==0 ||
    !Number.isFinite(body.width) || Math.abs(body.width-.35)>.01 ||
    !Number.isFinite(body.height) || Math.abs(body.height-.35)>.01 ||
    !Number.isFinite(body.eyeHeight) || body.eyeHeight<=0 || !Number.isFinite(caps.swimTargetYOffset) ||
    Math.abs(caps.swimTargetYOffset-Math.max(.05,(1-body.height)/2))>1e-6)
  throw new Error('Pufferfish native puff-zero geometry/capabilities differ');
await bot.waitForChunksToLoad();
const sourceWater=p=>{
  const block=bot.blockAt(p);
  return block?.name==='water'&&Number(block.getProperties().level)===0;
};
function submerged(){
  const p=body.position, half=body.width/2, top=p.y+Math.max(body.height,body.eyeHeight);
  for(let y=Math.floor(p.y+1e-7);y<Math.ceil(top-1e-7);y++)
    for(let x=Math.floor(p.x-half+1e-7);x<Math.ceil(p.x+half-1e-7);x++)
      for(let z=Math.floor(p.z-half+1e-7);z<Math.ceil(p.z+half-1e-7);z++)
        if(!sourceWater(new Vec3(x,y,z))||!sourceWater(new Vec3(x,y+1,z)))return false;
  return true;
}
for(let x=308;x<=335;x++)for(let z=108;z<=112;z++){
  if(bot.blockAt(new Vec3(x,-61,z))?.name!=='stone')throw new Error('Pufferfish bottom support differs');
  for(let y=-60;y<=-54;y++)
    if(!sourceWater(new Vec3(x,y,z)))throw new Error('Pufferfish source-water corridor differs');
}
if(!submerged())throw new Error('Initial Pufferfish body/eyes not wholly submerged');
const inventory=JSON.stringify(bot.inventory.slots), health=bot.health, start=Date.now();
const initialGeometry={width:body.width,height:body.height,eyeHeight:body.eyeHeight};
const initialCapabilities={...caps}, offset=caps.swimTargetYOffset;
const trace=[],paths=[],resets=[],puffUpdates=[];
let samples=0,pathEvents=0,escaped=false,routeError=null,phase='route';
const sample=()=>{
  samples++;
  if(!body.isInWater||!submerged())escaped=true;
  if(samples%4===0){
    if(trace.length===64)trace.shift();
    trace.push({phase,p:body.position.clone(),v:body.velocity.clone(),ground:body.onGround,puff:metadata[puffIndex]??null,
      width:body.width,height:body.height});
  }
};
const path=result=>{
  pathEvents++;
  if(paths.length===16)paths.shift();
  paths.push({status:result.status,totalNodes:result.path.length,
    hasEdits:result.path.some(node=>node.toBreak.length||node.toPlace.length),
    hasParkour:result.path.some(node=>node.parkour===true),
    nodes:result.path.slice(0,16).map(node=>({x:node.x,y:node.y,z:node.z}))});
};
const reset=reason=>{if(resets.length<16)resets.push(reason);};
const update=entity=>{
  if(entity!==body||puffUpdates.length===16)return;
  const puff=metadata[puffIndex]??null;
  if(!puffUpdates.length||puffUpdates[puffUpdates.length-1].puff!==puff)
    puffUpdates.push({at:Date.now(),puff,width:body.width,height:body.height,eyeHeight:body.eyeHeight,
      sameEntity:entity===bot.entity,sameMetadata:entity.metadata===metadata});
};
const movements=new Movements(bot);
movements.canDig=false;movements.allowParkour=false;movements.allowSprinting=false;
movements.allow1by1towers=false;movements.scafoldingBlocks=[];movements.allowFreeMotion=false;
movements.exclusionAreasStep.push(block=>!block.position || block.position.x<308 || block.position.x>335 ||
  block.position.z<108 || block.position.z>112 || block.position.y< -60 || block.position.y> -55 ? 200 : 0);
bot.pathfinder.enablePathShortcut=false;bot.pathfinder.setMovements(movements);
bot.on('physicsTick',sample);bot.on('path_update',path);bot.on('path_reset',reset);bot.on('entityUpdate',update);
try{
  if(selectedPhase==='puff-change'){
    // This new look is only a listener gate. Coordinator MUST also observe the following route running.
    await bot.look(body.yaw,body.pitch,true);
  }
  const target=selectedPhase==='ascent'?new Vec3(310,-58,110):new Vec3(334,-57,110);
  try{await bot.pathfinder.goto(new goals.GoalBlock(target.x,target.y,target.z));}
  catch(error){
    routeError={name:error.name,message:error.message};
    if(selectedPhase!=='puff-change'||error.name!=='NoPath'||
        error.message!=='Native route failed: route_pufferfish_puff_changed')throw error;
  }
  const afterRoute={at:Date.now(),p:body.position.clone(),v:body.velocity.clone(),ground:body.onGround};
  if(selectedPhase==='ascent'){
    if(routeError || bot.entity!==body || !body.position.floored().equals(target) ||
        body.position.distanceTo(new Vec3(target.x+.5,target.y+offset,target.z+.5))>.25 ||
        body.position.y<origin.y+.5 || !submerged() || escaped ||
        (metadata[puffIndex]!==undefined&&metadata[puffIndex]!==0) ||
        body.width!==initialGeometry.width || body.height!==initialGeometry.height || body.eyeHeight!==initialGeometry.eyeHeight)
      throw new Error('Short unchanged-puff submerged ascent differs');
  }else{
    if(!routeError)throw new Error('Expected active-route puff-change rejection missing');
    phase='after-terminal';await bot.waitForTicks(3);
    if(metadata[puffIndex]!==1 || Math.abs(body.width-.49)>.01 || Math.abs(body.height-.49)>.01 ||
        !Number.isFinite(body.eyeHeight) || body.eyeHeight<=initialGeometry.eyeHeight || !submerged())
      throw new Error('Native changed puff metadata/geometry not hydrated');
  }
  if(bot.entity!==body || body.metadata!==metadata || !body.alive || JSON.stringify(bot.inventory.slots)!==inventory ||
      (selectedPhase==='ascent'&&bot.health!==health) || bot.vehicle || bot.currentWindow || bot.inventory.selectedItem ||
      Object.values(bot.controlState).some(Boolean) || bot.pathfinder.goal!==null || bot.pathfinder.isMoving() ||
      paths.some(row=>row.hasEdits||row.hasParkour))
    throw new Error('Pufferfish terminal/public-control/item state differs');
  return {start,end:Date.now(),selectedPhase,bodyUuid:body.uuid,initialPuff,initialGeometry,initialCapabilities,
    target,routeError,afterRoute,final:{p:body.position.clone(),v:body.velocity.clone(),ground:body.onGround,
      puff:metadata[puffIndex]??null,width:body.width,height:body.height,eyeHeight:body.eyeHeight,health:bot.health},
    inventoryUnchanged:true,samples,pathEvents,escaped,paths,resets,puffUpdates,trace,traceLimit:64,pathLimit:16,
    independentChecks:['native puff/geometry/contact context','new running route and positive progress before puff mutation',
      'exact terminal action/no subsequent route','release/ownership']};
}catch(error){
  console.log(JSON.stringify({selectedPhase,phase,p:body.position,routeError,samples,pathEvents,escaped,
    puff:metadata[puffIndex]??null,width:body.width,height:body.height,paths,resets,puffUpdates,trace}));
  throw error; // No retry, rollback or cleanup mutation.
}finally{
  bot.removeListener('physicsTick',sample);bot.removeListener('path_update',path);
  bot.removeListener('path_reset',reset);bot.removeListener('entityUpdate',update);
}
