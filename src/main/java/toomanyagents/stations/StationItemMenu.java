package toomanyagents.stations;

import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** A native one-for-one operation. Only taking the preview consumes input; closing returns all unused input. */
public final class StationItemMenu extends AbstractContainerMenu {
    private final BooleanSupplier valid;
    private final UnaryOperator<ItemStack> convert;
    private final boolean server;
    private final SimpleContainer input = new SimpleContainer(1) {
        @Override public void setChanged() { super.setChanged(); StationItemMenu.this.slotsChanged(this); }
    };
    private final SimpleContainer output = new SimpleContainer(1);

    public StationItemMenu(int id, Inventory inventory) { this(id, inventory, () -> true, stack -> ItemStack.EMPTY); }

    public StationItemMenu(int id, Inventory inventory, BooleanSupplier valid, UnaryOperator<ItemStack> convert) {
        super(Stations.ITEM_MENU.get(), id);
        this.valid = valid;
        this.convert = convert;
        server = !inventory.player.level().isClientSide;
        addSlot(new Slot(input, 0, 44, 34) {
            @Override public boolean mayPlace(ItemStack stack) { return stack.is(Stations.STATION_ITEM.get()); }
        });
        addSlot(new Slot(output, 0, 116, 34) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) { return stillValid(player) && !input.isEmpty(); }
            @Override public void onTake(Player player, ItemStack stack) {
                input.removeItem(0, 1);
                refreshResult();
                super.onTake(player, stack);
            }
        });
        for (int row=0; row<3; row++) for (int col=0; col<9; col++)
            addSlot(new Slot(inventory, 9+row*9+col, 8+col*18, 84+row*18));
        for (int col=0; col<9; col++) addSlot(new Slot(inventory, col, 8+col*18, 142));
    }

    private void refreshResult() {
        if (!server) return;
        var stack = input.getItem(0);
        output.setItem(0, valid.getAsBoolean() && stack.is(Stations.STATION_ITEM.get()) ? convert.apply(stack.copyWithCount(1)) : ItemStack.EMPTY);
    }
    @Override public void slotsChanged(Container container) { refreshResult(); }
    @Override public void broadcastChanges() { refreshResult(); super.broadcastChanges(); }
    @Override public boolean stillValid(Player player) { return valid.getAsBoolean(); }
    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (!stillValid(player)) return;
        refreshResult();
        super.clicked(slot, button, type, player);
    }
    @Override public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) { return slot.container != output && super.canTakeItemForPickAll(stack, slot); }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index<0 || index>=slots.size() || !stillValid(player)) return ItemStack.EMPTY;
        var slot=slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        var stack=slot.getItem(); var original=stack.copy();
        boolean moved=index<2 ? moveItemStackTo(stack,2,38,index==1)
            : stack.is(Stations.STATION_ITEM.get()) ? moveItemStackTo(stack,0,1,false)
            : index<29 ? moveItemStackTo(stack,29,38,false) : moveItemStackTo(stack,2,29,false);
        if (!moved || stack.getCount()==original.getCount()) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player,stack);
        broadcastChanges();
        return original;
    }
    @Override public void removed(Player player) {
        super.removed(player);
        if (server) { output.clearContent(); clearContainer(player,input); }
    }

    /** Retains all unrelated item components, replacing only carried station settings. */
    public static ItemStack copy(ItemStack input, Station station) {
        input.set(Stations.STATION_DATA.get(),station.settings());
        return input;
    }
    public static ItemStack project(ItemStack input, String projectId) {
        var settings=input.getOrDefault(Stations.STATION_DATA.get(),Station.BLANK);
        return copy(input,settings.withProject(projectId));
    }
}
