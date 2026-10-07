// Fixture inventory contains a damaged pickaxe and items with native components.
// Compare these values to Item.fromNotch applied to independently decoded native
// stack bytes, then check roundtrip bytes before declaring Item compatibility.
return bot.inventory.items().map(item => ({
  slot: item.slot, type: item.type, name: item.name, count: item.count,
  durabilityUsed: item.durabilityUsed, maxDurability: item.maxDurability,
  components: item.components, removedComponents: item.removedComponents,
  enchants: item.enchants, customName: item.customName, customLore: item.customLore,
}));
