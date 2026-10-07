const hit = bot.world.raycast(new Vec3(1.5, -58, 2.5), new Vec3(0, -1, 0), 5);
if (!hit || hit.name !== 'stone_slab' || hit.face !== 1 || hit.intersect.y !== -59)
  throw new Error('Ray did not hit the slab top');
const block = bot.world.getBlock(new Vec3(1, -60, 2));
if (block.face !== undefined || block.intersect !== undefined) throw new Error('Ray hit mutated the cached block');
const look = new goals.GoalLookAtBlock(new Vec3(3, -60, 2), bot.world);
const breaking = new goals.GoalBreakBlock(3, -60, 2, bot);
const node = new Vec3(4, -60, 2);
if (!look.isEnd(node) || !breaking.isEnd(node)) throw new Error('Visible block goal failed');
return { block: hit.name, face: hit.face, intersect: hit.intersect, look: true, breaking: true };
