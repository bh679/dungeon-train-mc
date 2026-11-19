package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.util.LogFirstN;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

/**
 * Keeps the ores and gear metals DT has turned off ({@link DisabledModContent}) out of what BetterNether's
 * own structures place. Their ore features are already vetoed and their loot tables already stripped
 * ({@link StripDisabledItemsLootModifier}); this covers the rest — an ore block written straight into a
 * structure (it becomes netherrack) and ingots, nuggets or raw ore baked into a container's inventory (the
 * slot is emptied). BetterNether's templates carry none of those today, so this is a safety net for its
 * procedural pieces and future updates. Run once per chunk a structure places into, over that part of it.
 */
public final class StructureDisabledContentCleanup {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN ERRORS = new LogFirstN(5);
    private static final BlockState REPLACEMENT = Blocks.NETHERRACK.defaultBlockState();

    private StructureDisabledContentCleanup() {}

    /** Clean {@code box} (the structure's part of one chunk) in {@code level}. */
    public static void clean(WorldGenLevel level, BoundingBox box) {
        try {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        pos.set(x, y, z);
                        cleanBlock(level, pos);
                    }
                }
            }
        } catch (Throwable t) {
            ERRORS.error(LOGGER, "[DungeonTrain] disabled-content cleanup of a structure failed in " + box, t);
        }
    }

    private static void cleanBlock(WorldGenLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;
        Block block = state.getBlock();
        if (DisabledModContent.isDisabledOreBlock(BuiltInRegistries.BLOCK.getKey(block))) {
            level.setBlock(pos, REPLACEMENT, Block.UPDATE_CLIENTS);
            return;
        }
        if (!state.hasBlockEntity()) return;
        BlockEntity entity = level.getBlockEntity(pos);
        // A loot chest rolls on first look: reading its slots here would roll it at worldgen time. Its loot is
        // stripped when it does roll ({@link StripDisabledItemsLootModifier}).
        if (entity instanceof RandomizableContainerBlockEntity loot && loot.getLootTable() != null) return;
        if (entity instanceof Container container) emptyDisabledSlots(container);
    }

    /** Empty every slot holding a disabled item. Returns how many were emptied. */
    static int emptyDisabledSlots(Container container) {
        int emptied = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && DisabledModContent.isDisabledItem(stack)) {
                container.setItem(slot, ItemStack.EMPTY);
                emptied++;
            }
        }
        return emptied;
    }
}
