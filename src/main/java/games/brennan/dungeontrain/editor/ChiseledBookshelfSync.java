package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Re-syncs a chiseled bookshelf's slot-occupied blockstate (its "how many
 * books does it look like it's holding" texture) after loot is rolled
 * straight into its block-entity NBT/components.
 *
 * <p>Vanilla only recomputes {@link ChiseledBookShelfBlock#SLOT_OCCUPIED_PROPERTIES}
 * from inside {@code ChiseledBookShelfBlockEntity}'s {@code setItem}/
 * {@code removeItem} — the normal player-interaction path. Every loot-roll
 * call site in this mod writes items directly into the BE (via
 * {@code loadCustomOnly}/{@code loadWithComponents}), which fills the
 * inventory but never touches the blockstate, so without this a rolled
 * bookshelf keeps whatever slot texture it already had (usually empty)
 * regardless of what actually rolled into it.</p>
 */
public final class ChiseledBookshelfSync {

    private ChiseledBookshelfSync() {}

    /** No-op for every block except a chiseled bookshelf. */
    public static void syncIfNeeded(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof ChiseledBookShelfBlockEntity cbs)) return;
        BlockState state = level.getBlockState(pos);
        for (int i = 0; i < ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.size(); i++) {
            BooleanProperty property = ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(i);
            state = state.setValue(property, !cbs.getItem(i).isEmpty());
        }
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }
}
