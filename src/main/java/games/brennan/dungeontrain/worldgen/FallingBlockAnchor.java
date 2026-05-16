package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.ship.Shipyards;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Replaces gravity-affected blocks (any {@link Fallable} block — covers
 * {@link FallingBlock} subclasses plus {@code BrushableBlock} for suspicious
 * sand/gravel) directly above a freshly-carved corridor or tunnel cell with
 * a stable equivalent so they can't fall onto the rails or into the tunnel
 * interior.
 *
 * <p><b>Why anchoring is needed even with flag-2 writes.</b> Vanilla worldgen
 * places sand/gravel via {@code FallingBlock.onPlace}, which schedules a tick.
 * Chunk save persists those scheduled ticks. When the chunk loads in-game the
 * tick fires, {@code FallingBlock.tick} sees air below (because we carved it),
 * and a {@code FallingBlockEntity} spawns. {@link Block#UPDATE_CLIENTS}
 * placement avoids triggering a fresh neighbour cascade but does not cancel
 * the persisted scheduled tick. Replacing the bottommost falling block with a
 * stable block makes {@code isFree(below)} return false on the cell above the
 * anchor, breaking the cascade for the entire stack above.</p>
 *
 * <p>One anchor per (x, z) column is enough — vanilla's fall check looks only
 * at the cell directly below the falling block, so a single stable support at
 * the bottom of a stack stabilises everything resting on it.</p>
 *
 * <p>Two overloads mirror the {@code setIfNeeded} / {@code setIfNeededWorldgen}
 * split used elsewhere: the runtime overload checks
 * {@link Shipyards#of(ServerLevel)} so we never overwrite ship-owned voxels
 * (carriages, other Sable ships); the worldgen overload skips the check
 * because no ships exist at chunk gen.</p>
 */
public final class FallingBlockAnchor {

    /**
     * Concrete-block-instance → stable-equivalent state. Lookup is by
     * reference equality on the {@link Block} singleton, so this is O(1)
     * via {@link java.util.IdentityHashMap}-style behaviour without needing
     * the IdentityHashMap (vanilla {@link Block} instances override
     * neither {@code equals} nor {@code hashCode}).
     */
    private static final Map<Block, BlockState> STABLE = Map.ofEntries(
        Map.entry(Blocks.SAND, Blocks.SANDSTONE.defaultBlockState()),
        Map.entry(Blocks.RED_SAND, Blocks.RED_SANDSTONE.defaultBlockState()),
        Map.entry(Blocks.GRAVEL, Blocks.STONE.defaultBlockState()),
        Map.entry(Blocks.SUSPICIOUS_SAND, Blocks.SANDSTONE.defaultBlockState()),
        Map.entry(Blocks.SUSPICIOUS_GRAVEL, Blocks.STONE.defaultBlockState()),
        Map.entry(Blocks.WHITE_CONCRETE_POWDER, Blocks.WHITE_CONCRETE.defaultBlockState()),
        Map.entry(Blocks.ORANGE_CONCRETE_POWDER, Blocks.ORANGE_CONCRETE.defaultBlockState()),
        Map.entry(Blocks.MAGENTA_CONCRETE_POWDER, Blocks.MAGENTA_CONCRETE.defaultBlockState()),
        Map.entry(Blocks.LIGHT_BLUE_CONCRETE_POWDER, Blocks.LIGHT_BLUE_CONCRETE.defaultBlockState()),
        Map.entry(Blocks.YELLOW_CONCRETE_POWDER, Blocks.YELLOW_CONCRETE.defaultBlockState()),
        Map.entry(Blocks.LIME_CONCRETE_POWDER, Blocks.LIME_CONCRETE.defaultBlockState()),
        Map.entry(Blocks.PINK_CONCRETE_POWDER, Blocks.PINK_CONCRETE.defaultBlockState()),
        Map.entry(Blocks.GRAY_CONCRETE_POWDER, Blocks.GRAY_CONCRETE.defaultBlockState()),
        Map.entry(Blocks.LIGHT_GRAY_CONCRETE_POWDER, Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState()),
        Map.entry(Blocks.CYAN_CONCRETE_POWDER, Blocks.CYAN_CONCRETE.defaultBlockState()),
        Map.entry(Blocks.PURPLE_CONCRETE_POWDER, Blocks.PURPLE_CONCRETE.defaultBlockState()),
        Map.entry(Blocks.BLUE_CONCRETE_POWDER, Blocks.BLUE_CONCRETE.defaultBlockState()),
        Map.entry(Blocks.BROWN_CONCRETE_POWDER, Blocks.BROWN_CONCRETE.defaultBlockState()),
        Map.entry(Blocks.GREEN_CONCRETE_POWDER, Blocks.GREEN_CONCRETE.defaultBlockState()),
        Map.entry(Blocks.RED_CONCRETE_POWDER, Blocks.RED_CONCRETE.defaultBlockState()),
        Map.entry(Blocks.BLACK_CONCRETE_POWDER, Blocks.BLACK_CONCRETE.defaultBlockState())
    );

    private static final BlockState FALLBACK_STABLE = Blocks.STONE.defaultBlockState();

    private FallingBlockAnchor() {}

    /**
     * Resolve the stable equivalent for a falling block. Returns {@code null}
     * when {@code falling}'s block is not a {@link FallingBlock} subclass —
     * callers should treat that as a no-op.
     *
     * <p>Visible for unit tests.</p>
     */
    @Nullable
    static BlockState stableEquivalent(BlockState falling) {
        Block block = falling.getBlock();
        // Fallable covers both FallingBlock subclasses (sand, gravel, anvils,
        // concrete powders, scaffolding) AND BrushableBlock (suspicious
        // sand/gravel), which extends BaseEntityBlock — not FallingBlock —
        // but still implements Fallable to participate in support-loss falls.
        if (!(block instanceof Fallable)) return null;
        BlockState mapped = STABLE.get(block);
        return mapped != null ? mapped : FALLBACK_STABLE;
    }

    /**
     * Runtime anchor — checks {@link Shipyards#of(ServerLevel)} to avoid
     * overwriting ship-owned voxels. No-op when the block at {@code pos}
     * isn't a {@link FallingBlock}.
     */
    public static void anchorAt(ServerLevel level, BlockPos pos) {
        if (Shipyards.of(level).isInShip(pos)) return;
        BlockState existing = level.getBlockState(pos);
        BlockState anchor = stableEquivalent(existing);
        if (anchor == null) return;
        SilentBlockOps.setBlockSilent(level, pos, anchor);
    }

    /**
     * Worldgen anchor — direct {@code level.setBlock} with
     * {@link Block#UPDATE_CLIENTS}. No ship guard (no ships exist at chunk
     * gen). No-op when the block at {@code pos} isn't a {@link FallingBlock}.
     */
    public static void anchorAtWorldgen(WorldGenLevel level, BlockPos pos) {
        BlockState existing = level.getBlockState(pos);
        BlockState anchor = stableEquivalent(existing);
        if (anchor == null) return;
        level.setBlock(pos, anchor, Block.UPDATE_CLIENTS);
    }
}
