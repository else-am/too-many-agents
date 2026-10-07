let phase='dismount';const out={};
try{
 let dismountEvents=0;bot.on('dismount',()=>dismountEvents++);
 if(bot.dismount()!==undefined)throw new Error('dismount returned a value');
 let ticks=0;while(bot.vehicle&&ticks<40){await bot.waitForTicks(1);ticks++;}
 if(bot.vehicle)throw new Error('Vehicle remained after 40 ticks');
 await bot.waitForTicks(2);if(bot.vehicle)throw new Error('Vehicle returned after dismount');
 out.dismount={vehicle:bot.vehicle,events:dismountEvents,ticks,position:bot.entity.position.clone()};
 const ids=bot.registry.itemsByName;
 const isSpeed=i=>i?.name==='potion'&&i.customName&&ChatMessage.fromNotch(i.customName).toString()==='Native speed test';
 const unrelated=()=>JSON.stringify(bot.inventory.slots.filter(Boolean).filter(i=>!isSpeed(i)&&!['milk_bucket','bucket','glass_bottle'].includes(i.name)).map(i=>({name:i.name,count:i.count,components:i.components})).sort((a,b)=>JSON.stringify(a).localeCompare(JSON.stringify(b))));
 const baseline=unrelated();
 phase='potion';const potion=bot.inventory.slots.find(isSpeed);if(!potion)throw new Error('Named speed potion missing');
 const pBefore=bot.inventory.count(ids.potion.id),gBefore=bot.inventory.count(ids.glass_bottle.id);
 await bot.equip(potion,'hand');await bot.consume();
 out.potion={before:pBefore,after:bot.inventory.count(ids.potion.id),bottlesBefore:gBefore,bottlesAfter:bot.inventory.count(ids.glass_bottle.id),effects:JSON.parse(JSON.stringify(bot.entity.effects)),Speed:bot.registry.effectsByName.Speed};
 console.log('Potion: '+JSON.stringify(out.potion));
 if(out.potion.after!==pBefore-1||out.potion.bottlesAfter!==gBefore+1)throw new Error('Immediate potion consumption/remainder differs');
 phase='milk';const milk=bot.inventory.slots.find(i=>i?.name==='milk_bucket');if(!milk)throw new Error('Milk missing');
 const mBefore=bot.inventory.count(ids.milk_bucket.id),bBefore=bot.inventory.count(ids.bucket.id);
 await bot.equip(milk,'hand');await bot.consume();
 out.milk={before:mBefore,after:bot.inventory.count(ids.milk_bucket.id),bucketsBefore:bBefore,bucketsAfter:bot.inventory.count(ids.bucket.id),effects:JSON.parse(JSON.stringify(bot.entity.effects))};
 if(out.milk.after!==mBefore-1||out.milk.bucketsAfter!==bBefore+1||Object.keys(bot.entity.effects).length)throw new Error('Immediate milk consumption/remainder/effects differs');
 if(unrelated()!==baseline)throw new Error('Unrelated inventory changed');
 phase='sign';const sign=bot.blockAt(new Vec3(15,-59,7));if(!sign?.name.includes('sign'))throw new Error('Sign fixture missing');
 await bot.unequip('hand');await bot.activateBlock(sign);
 if(bot.updateSign(sign,"Native sign\nVerified")!==undefined)throw new Error('updateSign returned a value');
 await bot.waitForTicks(2);
 out.sign={name:sign.name,position:sign.position,text:bot.blockAt(sign.position).getSignText()};
 const front=out.sign.text[0].split('\n');if(front[0]!=='Native sign'||front[1]!=='Verified')throw new Error('Sign text differs');
 phase='player game';const game=bot.game;await bot.waitForTicks(2);
 out.state={players:bot.players,uuidToUsername:bot.uuidToUsername,stableGame:bot.game===game,game:{difficulty:game.difficulty,hardcore:game.hardcore,levelType:game.levelType,maxPlayers:game.maxPlayers,serverViewDistance:game.serverViewDistance}};
 out.unrelatedInventoryUnchanged=true;return out;
}catch(e){console.log('Failure phase: '+phase);console.log('Completed evidence: '+JSON.stringify(out));throw e;}
