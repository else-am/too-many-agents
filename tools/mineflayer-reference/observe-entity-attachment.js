// Prepared only: fresh empty Cow + exact reachable horse; entity-attachment-scenarios.md.
const expectedBodyUuid='REPLACE_WITH_NEW_ATTACHMENT_BODY_UUID';
const expectedMountUuid='REPLACE_WITH_NEW_ATTACHMENT_HORSE_UUID';
const body=bot.entity, mount=Object.values(bot.entities).find(entity=>entity.uuid===expectedMountUuid);
const origin=new Vec3(360.5,-60,110.5);
if(!(body instanceof Entity)||body.uuid!==expectedBodyUuid||body.name!=='cow'||!body.alive||!body.onGround||
    body.isSleeping||body.isInWater||body.isInLava||body.position.distanceTo(origin)>.2||
    bot.vehicle!==null||body.vehicle!==null||body.passengers.length||bot.heldItem||
    bot.inventory.slots.some(Boolean)||bot.currentWindow||bot.inventory.selectedItem||
    Object.values(bot.controlState).some(Boolean))throw new Error('Fresh unmounted empty body fixture differs');
if(!(mount instanceof Entity)||mount.uuid!==expectedMountUuid||mount.name!=='horse'||!mount.alive||
    !mount.onGround||mount.passengers.length||mount.vehicle!==null||mount.isInWater||mount.isInLava||
    !Number.isFinite(body.width)||body.width<=0||!Number.isFinite(body.height)||body.height<=0||
    !Number.isFinite(mount.width)||mount.width<=0||!Number.isFinite(mount.height)||mount.height<=0||
    !Number.isFinite(body.health)||body.health<=0||!Number.isFinite(mount.health)||mount.health<=0)
  throw new Error('Observed grounded ordinary horse/native geometry differs');
await bot.waitForChunksToLoad();
for(let x=358;x<=365;x++)for(let z=108;z<=114;z++){
  if(bot.blockAt(new Vec3(x,-61,z))?.name!=='stone')throw new Error('Attachment floor unknown/different');
  for(let y=-60;y<=-55;y++)
    if(bot.blockAt(new Vec3(x,y,z))?.name!=='air')throw new Error('Attachment clearance unknown/different');
}
const inventory=JSON.stringify(bot.inventory.slots), selection=bot.quickBarSlot;
const bodyPosition=body.position,mountPosition=mount.position,health=bot.health,mountHealth=mount.health,start=Date.now();
const events=[],errors=[];
let phase='baseline',attachCount=0,detachCount=0;
const view=()=>({body:body.position.clone(),mount:mount.position.clone(),bodyVehicle:body.vehicle?.uuid??null,
  botVehicle:bot.vehicle?.uuid??null,passengers:mount.passengers.map(entity=>entity?.uuid??null)});
function observe(kind,args){
  const [subject,vehicle]=args;
  if(subject!==body)return;
  if(kind==='attach')attachCount++;else detachCount++;
  const shared=args.length===2&&subject instanceof Entity&&vehicle instanceof Entity&&
    subject===bot.entity&&vehicle===mount&&bot.entities[subject.id]===subject&&bot.entities[vehicle.id]===vehicle;
  const positions=body.position===bodyPosition&&mount.position===mountPosition&&
    body.position instanceof Vec3&&mount.position instanceof Vec3;
  const hydrated=kind==='attach'?
    body.vehicle===mount&&bot.vehicle===mount&&mount.passengers.length===1&&mount.passengers[0]===body:
    body.vehicle===null&&bot.vehicle===null&&!mount.passengers.includes(body);
  const items=JSON.stringify(bot.inventory.slots)===inventory&&bot.quickBarSlot===selection&&
    bot.currentWindow===null&&bot.inventory.selectedItem==null;
  if((!shared||!positions||!hydrated||!items)&&errors.length<8)
    errors.push({kind,shared,positions,hydrated,items});
  if(events.length<8)events.push({kind,at:Date.now(),shared,positions,hydrated,items,...view()});
}
const attached=(...args)=>observe('attach',args),detached=(...args)=>observe('detach',args);
bot.on('entityAttach',attached);bot.on('entityDetach',detached);
try{
  await bot.waitForTicks(3);
  if(attachCount||detachCount)throw new Error('Unchanged unmounted baseline emitted attachment');
  phase='mount';
  if(bot.mount(mount)!==undefined)throw new Error('Native mount return differs');
  for(let tick=0;bot.vehicle!==mount&&tick<40;tick++)await bot.waitForTicks(1);
  if(bot.vehicle!==mount||body.vehicle!==mount||mount.passengers.length!==1||mount.passengers[0]!==body||
      attachCount!==1||detachCount!==0||errors.length)throw new Error('Typed hydrated attachment missing/different');
  const mounted=view();
  await bot.waitForTicks(20);
  if(bot.vehicle!==mount||attachCount!==1||detachCount!==0||errors.length)
    throw new Error('Mounted relationship changed/replayed');
  phase='dismount';
  if(bot.dismount()!==undefined)throw new Error('Native dismount return differs');
  for(let tick=0;bot.vehicle!==null&&tick<40;tick++)await bot.waitForTicks(1);
  await bot.waitForTicks(10);
  if(bot.entity!==body||bot.vehicle!==null||body.vehicle!==null||mount.passengers.length||
      attachCount!==1||detachCount!==1||events.map(event=>event.kind).join(',')!=='attach,detach'||errors.length||
      bot.health!==health||mount.health!==mountHealth||JSON.stringify(bot.inventory.slots)!==inventory||
      bot.quickBarSlot!==selection||bot.currentWindow||bot.inventory.selectedItem||
      Object.values(bot.controlState).some(Boolean))throw new Error('Typed detach/replay/item/control cleanup differs');
  return {start,end:Date.now(),bodyUuid:body.uuid,mountUuid:mount.uuid,attachCount,detachCount,events,errors,
    mounted,final:view(),health,mountHealth,inventoryUnchanged:true,
    eventMeaning:'documented native vehicle relationship; not legacy attach_entity packet parity',
    independentChecks:['exact native mount identity/reach before invocation','actual body Passenger/vehicle NBT','release/ownership']};
}catch(error){
  console.log(JSON.stringify({phase,attachCount,detachCount,events,errors,state:view()}));
  throw error; // No retries, movement controls or error-handler cleanup mutations.
}finally{
  bot.removeListener('entityAttach',attached);bot.removeListener('entityDetach',detached);
}
