// Preauthored native fixture: see entities-native.md for setup and expected data.
// Observation only. Run after native/host/guest hydration integration.
const check = (ok, message) => { if (!ok) throw new Error(message); };
const observed = Object.values(bot.entities);
const byCustomName = text => observed.find(entity => entity.getCustomName()?.toString() === text);
const stand = byCustomName('Fixture subject');
const passenger = byCustomName('Fixture passenger');
const dropped = byCustomName('Fixture drop');
check(stand && passenger && dropped, 'Missing bounded entity fixture');
check(stand instanceof Entity && dropped instanceof Entity && bot.entity instanceof Entity, 'Entity class identity');
check(stand.position instanceof Vec3 && stand.velocity instanceof Vec3, 'Entity vectors are not Vec3');
const name = stand.getCustomName();
check(name instanceof ChatMessage, 'Custom name is not ChatMessage');
check(name.toMotd().includes('§6') && name.toMotd().includes('§l'), 'Custom name lost gold/bold formatting');
check(name.toHTML().includes('color:#FFAA00'), 'Custom name lost HTML formatting');
const equipmentNames = ['diamond_pickaxe', 'shield', 'iron_boots', 'iron_leggings', 'iron_chestplate', 'iron_helmet'];
check(stand.equipment.length === 6, 'Six equipment slots required');
check(stand.equipment.every((item, index) => item instanceof Item && item.name === equipmentNames[index]), 'Equipment order or Item identity');
check(stand.heldItem === stand.equipment[0] && stand.heldItem.durabilityUsed === 3, 'Held Item/damage differs');
check(passenger.vehicle === stand && stand.passengers.includes(passenger), 'Passenger/vehicle references not linked');
check(stand.vehicle === null && dropped.passengers.length === 0, 'Empty relationships differ');
check(passenger.equipment.length === 6 && passenger.equipment.every(item => item === null), 'Empty equipment slots differ');
const stack = dropped.getDroppedItem();
check(stack instanceof Item && stack.name === 'diamond_pickaxe' && stack.count === 1, 'Dropped Item type/count/class');
check(stack.durabilityUsed === 3, 'Dropped damage component missing');
const enchantments = stack.componentMap.get('enchantments')?.data.enchantments;
check(enchantments?.some(entry => Number.isInteger(entry.id) && entry.level === 3), 'Dropped enchantment component missing');
const itemName = ChatMessage.fromNotch(stack.customName);
check(itemName.toString() === 'Fixture pickaxe', 'Dropped custom item name missing');
check(stack.customLore?.length === 1 && ChatMessage.fromNotch(stack.customLore[0]).toString() === 'Fixture lore', 'Dropped lore missing');
const identity = { position: stand.position, velocity: stand.velocity, equipment: stand.equipment.slice() };
await bot.waitForTicks(3);
check(bot.entities[stand.id] === stand && bot.entities[dropped.id] === dropped, 'Unchanged Entity lost identity');
check(stand.position === identity.position && stand.velocity === identity.velocity, 'Unchanged Vec3 lost identity');
check(identity.equipment.every((item, index) => stand.equipment[index] === item), 'Unchanged equipment Item lost identity');
check(stand.getCustomName() instanceof ChatMessage && dropped.getDroppedItem() instanceof Item, 'Class identity lost after snapshot');
check(passenger.vehicle === stand && stand.passengers.includes(passenger), 'Relationships lost after snapshot');
const formats = Object.entries(bot.registry.chatFormattingById ?? {});
const chatFormat = formats.find(([, value]) => value.formatString === 'chat.type.text' && value.parameters.join(',') === 'sender,content');
check(chatFormat, 'Actual world chat format registry missing');
const formatted = ChatMessage.fromNetwork(Number(chatFormat[0]), { sender: 'Fixture', content: 'Hello' });
check(formatted.toString() === '<Fixture> Hello', 'World chat format mapping differs');
return {
  ids: { stand: stand.id, passenger: passenger.id, dropped: dropped.id },
  name: name.toString(), nameMotd: name.toMotd(), equipment: equipmentNames,
  item: { name: stack.name, damage: stack.durabilityUsed, enchants: enchantments, customName: itemName.toString() },
  vehicle: passenger.vehicle.id, passengers: stand.passengers.map(entity => entity.id),
  formatted: formatted.toString(), chatFormats: formats,
};
