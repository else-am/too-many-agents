// XP prerequisite: chest slot0 has a diamond pickaxe with Mending I and damage10;
// native orb Value20 at (13.5,-60,3.5), initially outside body's pickup bounds.
const w=await bot.openChest(bot.blockAt(new Vec3(12,-60,2)));
const tool=w.slots[0];
if(tool?.name!=='diamond_pickaxe' || tool.durabilityUsed!==10) throw new Error('Missing Mending fixture');
await bot.equip(tool,'hand');await w.close();
const before=bot.experience.points, reference=bot.experience;
let events=0;
bot.on('experience',()=>{if(bot.experience!==reference)throw new Error('Experience object identity changed');events++;});
const m=new Movements(bot);m.canDig=false;m.allowParkour=false;m.scafoldingBlocks=[];
bot.pathfinder.setMovements(m);
await bot.pathfinder.goto(new goals.GoalBlock(13,-60,3));
await bot.waitForTicks(5);
if(bot.heldItem.durabilityUsed!==0 || bot.experience.points!==before+15 || events<1)
  throw new Error('Native Mending/XP distribution failed');
return {damage:bot.heldItem.durabilityUsed,before,experience:bot.experience,events,position:bot.entity.position};
