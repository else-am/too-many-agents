let phase='observe';const out={};
try{
 const boat=Object.values(bot.entities).find(e=>e.uuid==='b6ec3bf1-8eac-461f-a281-88b2a6f170d0');if(!boat)throw new Error('Boat missing');
 const capture=()=>({boatPosition:boat.position.clone(),boatYaw:boat.yaw,bodyPosition:bot.entity.position.clone(),bodyYaw:bot.entity.yaw,vehicle:bot.vehicle?.uuid??null,passengers:boat.passengers.map(e=>e.uuid)});
 out.before=capture();console.log('Geometry: '+JSON.stringify(out.before));
 let mounts=0,dismounts=0;bot.on('mount',()=>mounts++);bot.on('dismount',()=>dismounts++);
 phase='mount';if(bot.mount(boat)!==undefined)throw new Error('mount returned a value');
 let ticks=0;while(bot.vehicle!==boat&&ticks<40){await bot.waitForTicks(1);ticks++;}
 if(bot.vehicle!==boat)throw new Error('Vehicle did not match boat');
 out.mounted=capture();console.log('Mounted native passenger inspection window: '+JSON.stringify(out.mounted));
 await bot.waitForTicks(40);
 phase='forward';if(bot.moveVehicle(0,1)!==undefined)throw new Error('moveVehicle returned a value');
 const start=boat.position.clone();await bot.waitForTicks(20);out.forward=capture();
 if(boat.position.distanceTo(start)<0.2)throw new Error('Boat did not move meaningfully');
 phase='turn';const yaw=boat.yaw;if(bot.moveVehicle(1,1)!==undefined)throw new Error('turn returned a value');
 await bot.waitForTicks(15);out.turn=capture();if(Math.abs(boat.yaw-yaw)<0.05)throw new Error('Boat heading did not change');
 phase='zero';bot.moveVehicle(0,0);await bot.waitForTicks(5);out.zero=capture();
 phase='mounted controls';bot.setControlState('forward',true);await bot.waitForTicks(5);bot.clearControlStates();bot.moveVehicle(0,0);await bot.waitForTicks(3);out.controlsReleased=capture();
 phase='dismount';if(bot.dismount()!==undefined)throw new Error('dismount returned a value');
 ticks=0;while(bot.vehicle&&ticks<40){await bot.waitForTicks(1);ticks++;}
 bot.clearControlStates();if(bot.vehicle)throw new Error('Vehicle remained after dismount');
 out.after=capture();out.events={mounts,dismounts};out.controls=bot.controlState;return out;
}catch(e){console.log('Failure phase: '+phase);console.log('Evidence: '+JSON.stringify(out));throw e;}
