// Two-deep pool; start at (12.5,-62,0.5), with no materials or terrain edits.
const m=new Movements(bot);m.canDig=false;m.allowParkour=false;m.allow1by1towers=false;m.scafoldingBlocks=[];
bot.pathfinder.setMovements(m);
const stops=[];
for(const y of [-61,-62]) {
  await bot.pathfinder.goto(new goals.GoalBlock(12,y,0));
  const p=bot.entity.position.clone();
  if(Math.floor(p.y)!==y) throw new Error('Wrong vertical water cell');
  stops.push(p);
}
return {stops};
