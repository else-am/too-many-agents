// Real bounded cache: table (11,-60,1), wall behind it (11,-60,0), sign (9,-60,2).
const table=bot.findBlock({matching:bot.registry.blocksByName.crafting_table.id});
if(!table?.position.equals(new Vec3(11,-60,1))) throw new Error('Nearest table query failed');
const sign=bot.findBlock({matching:bot.registry.blocksByName.oak_sign.id,maxDistance:4,
  useExtraInfo:block=>block.getSignText()[0].includes('Query fixture')});
if(!sign?.position.equals(new Vec3(9,-60,2))) throw new Error('Extra-info sign query failed');
const wall=bot.blockAt(new Vec3(11,-60,0));
if(!bot.canSeeBlock(table) || bot.canSeeBlock(wall)) throw new Error('Native cache occlusion failed');
// Native interaction establishes view while this script owns the body; ambient
// look controls may reset pitch between scripts.
const window=await bot.openBlock(table);
await window.close();
const cursor=bot.blockAtCursor(8);
if(!cursor?.position.equals(table.position)) throw new Error('Actual body cursor did not select table');
let unknown;
try {bot.blockAtCursor(100,null,{...bot.entity,yaw:0,pitch:0});} catch(error) {unknown=error.message;}
if(unknown!=='World query entered an unknown block cell') throw new Error('Unknown cache ray was not rejected');
return {table:table.position,sign:sign.getSignText(),cursor:{name:cursor.name,face:cursor.face,intersect:cursor.intersect},
  eyeHeight:bot.entity.eyeHeight,width:bot.entity.width,unknown};
