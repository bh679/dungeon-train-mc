package games.brennan.dungeontrain.block.engine;

import games.brennan.dungeontrain.registry.ModMenuTypes;
import games.brennan.dungeontrain.train.engine.DriftEngine;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Drifting Engine Block screen's container: the coal slot (in only, never out) over the player inventory, plus the coal count
 * synced as menu data so the gauge and the "more worlds" line read it without a packet of their own.
 */
public class EngineMenu extends AbstractContainerMenu {

    /** Where the coal slot sits on the screen texture. */
    public static final int COAL_X = 13, COAL_Y = 20;

    private final Container engine;
    private final ContainerData data;

    /** Client side: a stand-in container, filled by the menu's own slot sync. */
    public EngineMenu(int id, Inventory inventory) {
        this(id, inventory, new SimpleContainer(1), new SimpleContainerData(1));
    }

    public EngineMenu(int id, Inventory inventory, Container engine, ContainerData data) {
        super(ModMenuTypes.ENGINE_BLOCK.get(), id);
        checkContainerSize(engine, 1);
        checkContainerDataCount(data, 1);
        this.engine = engine;
        this.data = data;
        engine.startOpen(inventory.player);
        addSlot(new Slot(engine, 0, COAL_X, COAL_Y) {
            @Override public boolean mayPlace(ItemStack stack) { return stack.is(Items.COAL); }
            /** Coal goes in and burns; it never comes back out. */
            @Override public boolean mayPickup(Player player) { return false; }
            @Override public int getMaxStackSize() { return DriftEngine.MAX_COAL; }
        });
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 8 + col * 18, 142));
        addDataSlots(data);
    }

    /** Coal in the engine, as last synced. */
    public int coal() {
        return data.get(0);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        if (index == 0) {
            return ItemStack.EMPTY; // coal never comes back out
        } else if (stack.is(Items.COAL)) {
            if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
        } else {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return before;
    }

    @Override
    public boolean stillValid(Player player) {
        return engine.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        engine.stopOpen(player);
    }
}
