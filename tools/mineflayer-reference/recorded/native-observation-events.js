let phase='baseline';const out={};const records=[];const safe=v=>JSON.parse(JSON.stringify(v,(_,x)=>typeof x==='bigint'?String(x):x));
try{
 const self=bot.entity,map=self.effects,position=self.position;
 if(bot.isSleeping||bot.vehicle||Object.keys(map).length)throw new Error('Baseline awake/unmounted/effects prerequisite differs');
 const speedId=bot.registry.effectsByName.Speed.id;let adds=0,ends=0,crouches=0,uncrouches=0,moves=0,firstEffect;
 bot.on('entityEffect',(entity,effect)=>{if(entity===self){adds++;firstEffect=effect;records.push({event:'entityEffect',sameSelf:entity===bot.entity,sameMap:entity.effects===map,mapUpdated:map[effect.id]===effect,effect:safe(effect)});}});
 bot.on('entityEffectEnd',(entity,effect)=>{if(entity===self){ends++;records.push({event:'entityEffectEnd',sameSelf:entity===bot.entity,sameMap:entity.effects===map,mapRemoved:!map[effect.id],effect:safe(effect)});}});
 bot.on('entityCrouch',entity=>{if(entity===self){crouches++;records.push({event:'entityCrouch',sameSelf:entity===bot.entity,crouching:entity.crouching});}});
 bot.on('entityUncrouch',entity=>{if(entity===self){uncrouches++;records.push({event:'entityUncrouch',sameSelf:entity===bot.entity,crouching:entity.crouching});}});
 bot.on('move',old=>{moves++;records.push({event:'move',oldVec3:old instanceof Vec3,oldPosition:safe(old),yaw:self.yaw,position:self.position.clone()});});
 console.log('READY MARKER: observation handlers installed');
 phase='sneak';bot.setControlState('sneak',true);let ticks=0;
 while(!self.crouching&&ticks<40){await bot.waitForTicks(1);ticks++;}
 if(!self.crouching||!crouches)throw new Error('Crouch flag/event missing');
 console.log('SNEAK READY: native effect addition/removal observation waiting');
 phase='effect addition';ticks=0;while(!map[speedId]&&ticks<1000){await bot.waitForTicks(1);ticks++;}
 if(!map[speedId])throw new Error('Speed addition did not arrive');
 const effect=map[speedId],duration=effect.duration;let countdownTicks=0;
 phase='effect removal';
 while(map[speedId]&&countdownTicks<1000){
  if(map[speedId]!==effect||self.effects!==map||bot.entity!==self)throw new Error('Effect/entity/map identity changed');
  await bot.waitForTicks(1);countdownTicks++;
 }
 if(map[speedId])throw new Error('Speed removal did not arrive');
 out.effect={adds,ends,countdownTicks,initialDuration:duration,finalDuration:effect.duration,sameEventObject:firstEffect===effect};
 if(adds!==1||ends!==1||countdownTicks<40||firstEffect!==effect)throw new Error('Effect event/countdown contract differs');
 phase='uncrouch';bot.clearControlStates();ticks=0;while(self.crouching&&ticks<40){await bot.waitForTicks(1);ticks++;}
 if(self.crouching||!uncrouches)throw new Error('Uncrouch flag/event missing');
 phase='look move';const movesBefore=moves,yawBefore=self.yaw;
 await bot.look(yawBefore+0.5,0,true);await bot.waitForTicks(2);
 if(moves<=movesBefore||Math.abs(self.yaw-yawBefore)<0.1)throw new Error('Look move event/yaw missing');
 out.identities={sameEntity:bot.entity===self,sameEffectsMap:self.effects===map,samePosition:self.position===position};
 out.events=records;out.final={crouching:self.crouching,effects:self.effects,controls:bot.controlState,yaw:self.yaw,position:self.position};return out;
}catch(e){console.log('Failure phase: '+phase);console.log('Evidence: '+JSON.stringify(safe({out,records})));throw e;}
