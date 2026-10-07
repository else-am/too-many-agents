// Shared native/reference fixture: top stone slab at (1,-60,2), an oak sign
// at (2,-60,2) with front text "Hello native", and diamond ore at (3,-60,2).
// Run after lighting settles; the native harness independently reads the world.
return [new Vec3(1, -60, 2), new Vec3(2, -60, 2), new Vec3(3, -60, 2)].map(position => {
  const block = bot.blockAt(position);
  if (!block || typeof block.then === 'function') throw new Error('Expected a synchronous loaded Block');
  return {
    name: block.name, stateId: block.stateId, position: block.position,
    properties: block.getProperties(), shapes: block.shapes,
    light: block.light, skyLight: block.skyLight, biome: block.biome.id,
    handHarvest: block.canHarvest(null) ?? null,
    pickaxeHarvest: block.canHarvest(bot.registry.itemsByName.diamond_pickaxe.id) ?? null,
    handTime: block.digTime(null, false, false, false),
    pickaxeTime: block.digTime(bot.registry.itemsByName.diamond_pickaxe.id, false, false, false),
    signText: block.getSignText?.(), blockEntity: block.blockEntity,
  };
});
