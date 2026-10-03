package toomanyagents;

import com.mojang.datafixers.util.Pair;
import java.util.function.BooleanSupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Both inventories are real server containers; vanilla packets handle every transfer. */
public final class AgentInventoryMenu extends AbstractContainerMenu {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, "too_many_agents");
    public static final DeferredHolder<MenuType<?>, MenuType<AgentInventoryMenu>> TYPE =
        MENUS.register("agent_inventory", () -> IMenuTypeExtension.create(AgentInventoryMenu::new));
    public static final int PLAYER_START = 41;
    public static final int PLAYER_X = 0;
    public static final int PLAYER_Y = 184;
    public final int entityId;
    public final String agentId;
    private final BooleanSupplier valid;
    private final Runnable save;

    private AgentInventoryMenu(int id, Inventory player, RegistryFriendlyByteBuf data) {
        this(id, player, new SimpleContainer(41), data.readVarInt(), data.readUtf(), () -> true, () -> {});
    }

    AgentInventoryMenu(int id, Inventory player, Container agent, int entityId, String agentId, BooleanSupplier valid, Runnable save) {
        super(TYPE.get(), id);
        this.entityId = entityId;
        this.agentId = agentId;
        this.valid = valid;
        this.save = save;
        var entity = player.player.level().getEntity(entityId);
        LivingEntity owner = agent instanceof Inventory inventory ? inventory.player
            : entity instanceof LivingEntity living ? living : player.player;
        addInventory(agent, owner, 0, 0);
        addInventory(player, player.player, PLAYER_X, PLAYER_Y);
    }

    private void addInventory(Container inventory, LivingEntity owner, int x, int y) {
        for (int index = 0; index < 36; index++) {
            int slotY = index < 9 ? 142 : 84 + (index / 9 - 1) * 18;
            addSlot(new Slot(inventory, index, x + 8 + index % 9 * 18, y + slotY));
        }
        for (int index = 36; index < 40; index++) {
            EquipmentSlot equipment = switch (index) { case 36 -> EquipmentSlot.FEET; case 37 -> EquipmentSlot.LEGS;
                case 38 -> EquipmentSlot.CHEST; default -> EquipmentSlot.HEAD; };
            final EquipmentSlot armor = equipment;
            ResourceLocation icon = switch (armor) {
                case FEET -> InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS;
                case LEGS -> InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS;
                case CHEST -> InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE;
                default -> InventoryMenu.EMPTY_ARMOR_SLOT_HELMET;
            };
            addSlot(new Slot(inventory, index, x + 8, y + 8 + (39 - index) * 18) {
                @Override public int getMaxStackSize() { return 1; }
                @Override public boolean mayPlace(ItemStack stack) { return stack.canEquip(armor, owner); }
                @Override public boolean mayPickup(Player player) {
                    return player.isCreative() || !EnchantmentHelper.has(getItem(), EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
                }
                @Override public void setByPlayer(ItemStack stack, ItemStack previous) {
                    owner.onEquipItem(armor, previous, stack);
                    super.setByPlayer(stack, previous);
                }
                @Override public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
                    return Pair.of(InventoryMenu.BLOCK_ATLAS, icon);
                }
            });
        }
        addSlot(new Slot(inventory, 40, x + 77, y + 62) {
            @Override public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
                return Pair.of(InventoryMenu.BLOCK_ATLAS, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD);
            }
        });
    }

    @Override public boolean stillValid(Player player) { return valid.getAsBoolean(); }

    @Override public void clicked(int slot, int button, ClickType click, Player player) {
        if (!stillValid(player)) return;
        super.clicked(slot, button, click, player);
        if (!player.level().isClientSide()) save.run();
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size() || !stillValid(player)) return ItemStack.EMPTY;
        Slot source = slots.get(index);
        if (!source.hasItem() || !source.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = source.getItem(), original = stack.copy();
        // Shift-click transfers to storage, never silently replaces somebody's equipment.
        int start = index < PLAYER_START ? PLAYER_START : 0;
        if (!moveItemStackTo(stack, start, start + 36, false)) return ItemStack.EMPTY;
        if (stack.isEmpty()) source.setByPlayer(ItemStack.EMPTY);
        else source.setChanged();
        source.onTake(player, stack);
        return original;
    }

    @Override public void removed(Player player) {
        super.removed(player); // Vanilla returns the cursor stack to the viewer, dropping overflow safely.
        if (!player.level().isClientSide()) save.run();
    }
}
