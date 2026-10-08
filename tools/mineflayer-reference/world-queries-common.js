// Shared subset of world-queries-native.js; no bounded-cache-only assertion.
const table = bot.findBlock({ matching: bot.registry.blocksByName.crafting_table.id });
if (!table?.position.equals(new Vec3(11,-60,1))) throw new Error('Nearest table query failed');
const sign = bot.findBlock({ matching: bot.registry.blocksByName.oak_sign.id, maxDistance:4,
  useExtraInfo: block => block.getSignText()[0].includes('Query fixture') });
if (!sign?.position.equals(new Vec3(9,-60,2))) throw new Error('Extra-info sign query failed');
const wall = bot.blockAt(new Vec3(11,-60,0));
const tableVisible = bot.canSeeBlock(table), wallVisible = bot.canSeeBlock(wall);
if (!tableVisible || wallVisible) throw new Error('Known fixture occlusion differs');
return { table:table.position, sign:sign.position, text:sign.getSignText()[0], tableVisible, wallVisible };
