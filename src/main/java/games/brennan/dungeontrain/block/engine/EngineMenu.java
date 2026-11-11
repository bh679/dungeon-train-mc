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

/**
 * The Drifting Engine Block screen's container: the input slot over the player inventory, plus the
 * banked coal and the whole carriage's coal (every engine in it) synced as menu data, so the gauge and
 * the "more worlds" line read them without a packet of their own. The input takes coal or charcoal only, up to what the banked coal leaves of
 * {@link DriftEngine#MAX_COAL}, and gives it back on request. The screen draws the faded coal an empty input shows.
 */
public class EngineMenu extends AbstractContainerMenu {

    /** Where the coal slot sits on the screen texture. */
    public static final int COAL_X = 13, COAL_Y = 20;

    private final Container engine;
    private final ContainerData data;

    /** Client side: a stand-in container, filled by the menu's own slot sync. */
    public EngineMenu(int id, Inventory inventory) {
        this(id, inventory, new SimpleContainer(1), new SimpleContainerData(2));
    }

    public EngineMenu(int id, Inventory inventory, Container engine, ContainerData data) {
        super(ModMenuTypes.ENGINE_BLOCK.get(), id);
        checkContainerSize(engine, 1);
        checkContainerDataCount(data, 2);
        this.engine = engine;
        this.data = data;
        engine.startOpen(inventory.player);
        addSlot(new Slot(engine, 0, COAL_X, COAL_Y) {
            @Override public boolean mayPlace(ItemStack stack) { return EngineBlockEntity.isFuel(stack); }
            @Override public int getMaxStackSize() { return Math.max(0, DriftEngine.MAX_COAL - banked()); }
            @Override public int getMaxStackSize(ItemStack stack) { return getMaxStackSize(); }
        });
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 84 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 8 + col * 18, 142));
        addDataSlots(data);
    }

    /** Coal the engine arrived with, as last synced: shown, never taken out. */
    public int banked() {
        return data.get(0);
    }

    /** Coal put in during this world: the input slot. */
    public int input() {
        ItemStack stack = slots.get(0).getItem();
        return EngineBlockEntity.isFuel(stack) ? stack.getCount() : 0;
    }

    /** This engine's fuel: banked plus input. */
    public int coal() {
        return banked() + input();
    }

    /**
     * Coal in every engine of this carriage together, as last synced: the worlds it can still reach. Never
     * less than this engine's own, which the slot shows a tick before the sync catches up.
     */
    public int carriageCoal() {
        return Math.max(data.get(1), coal());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        if (index == 0) {
            if (!moveItemStackTo(stack, 1, slots.size(), true)) return ItemStack.EMPTY;
        } else if (EngineBlockEntity.isFuel(stack)) {
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
