package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.editor.LootValue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;

/**
 * What a storage block aboard a drifting carriage holds, read the same way at open, close and release
 * so the three can be compared.
 *
 * <p>Read through NeoForge's item-handler capability, so it covers every vanilla storage block — chests,
 * barrels, shulker boxes, hoppers, dispensers, droppers, crafters, furnaces, brewing stands — and modded
 * storage that only exposes an item handler. A plain {@link Container} is the fallback for a block with
 * no capability registered. For a double chest the capability at either half is the combined inventory,
 * which is why {@link #cells} reports both halves.</p>
 */
public final class StorageContents {

    /**
     * Item total (the gift check), a slot-by-slot signature (any change at all), and the contents'
     * worth by {@link LootValue#stackScore} (what a deposit is credited to the echo by).
     */
    public record Snapshot(int itemCount, long sig, double value) {}

    private StorageContents() {}

    /** The contents at {@code pos}, or null when there is no storage there to read. */
    public static Snapshot read(Level level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler != null) {
            Accumulator acc = new Accumulator();
            for (int slot = 0; slot < handler.getSlots(); slot++) acc.add(handler.getStackInSlot(slot));
            return acc.snapshot();
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof Container container)) return null;
        Accumulator acc = new Accumulator();
        for (int slot = 0; slot < container.getContainerSize(); slot++) acc.add(container.getItem(slot));
        return acc.snapshot();
    }

    /**
     * The cells whose saved contents a change at {@code pos} touches: the block itself, plus the other
     * half of a double chest — the menu edits both, and each half's items are stored in its own cell.
     */
    public static List<BlockPos> cells(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return List.of(pos.immutable(), pos.relative(ChestBlock.getConnectedDirection(state)).immutable());
        }
        return List.of(pos.immutable());
    }

    /** Order-dependent fold: item, components and count per slot, so a take, swap or move all show. */
    private static final class Accumulator {
        private int count;
        private long sig = 1L;
        private double value;

        void add(ItemStack stack) {
            long cell = 0L;
            if (!stack.isEmpty()) {
                count += stack.getCount();
                value += LootValue.stackScore(stack);
                cell = 31L * ItemStack.hashItemAndComponents(stack) + stack.getCount();
            }
            sig = sig * 1_000_003L + cell;
        }

        Snapshot snapshot() { return new Snapshot(count, sig, value); }
    }
}
