package games.brennan.dungeontrain.train.engine;

import games.brennan.dungeontrain.block.engine.EngineBlock;
import games.brennan.dungeontrain.block.engine.EngineBlockEntity;
import games.brennan.dungeontrain.registry.ModBlocks;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Drifting Engine Blocks in placed boxes: finding one, and setting a fresh one down. Works at whatever
 * coordinates the box was placed at — world space for a room, shipyard space for a carriage after
 * assembly, just as the contents pass writes.
 */
public final class EngineStamp {

    private EngineStamp() {}

    /** A carriage box as a size: length along x, height along y, width along z. */
    public static Vec3i size(CarriageDims dims) {
        return new Vec3i(dims.length(), dims.height(), dims.width());
    }

    /** {@link #ensure(ServerLevel, BlockPos, Vec3i, int)} for a carriage box. */
    public static int ensure(ServerLevel level, BlockPos origin, CarriageDims dims, int coal) {
        return ensure(level, origin, size(dims), coal);
    }

    /** How many engines stand in the box, not counting the one at {@code except}. */
    public static int countEngines(ServerLevel level, BlockPos origin, Vec3i size, BlockPos except) {
        int n = 0;
        for (int x = 0; x < size.getX(); x++) {
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (!pos.equals(except) && level.getBlockState(pos).getBlock() instanceof EngineBlock) n++;
                }
            }
        }
        return n;
    }

    /** The engine in the box at {@code origin} of {@code size}, or null when there is none. */
    public static EngineBlockEntity find(ServerLevel level, BlockPos origin, Vec3i size) {
        for (int x = 0; x < size.getX(); x++) {
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (level.getBlockState(pos).getBlock() instanceof EngineBlock
                            && level.getBlockEntity(pos) instanceof EngineBlockEntity engine) {
                        return engine;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Make sure the box has an engine holding {@code coal}: the one already there, or a new one in place
     * of a stone-type block facing the room, else on a free floor cell ({@link EngineSpot}). Returns the
     * coal the box's engine holds afterwards, or -1 when there was no engine and nowhere to put one.
     */
    public static int ensure(ServerLevel level, BlockPos origin, Vec3i size, int coal) {
        EngineBlockEntity existing = find(level, origin, size);
        if (existing != null) {
            existing.setCoal(coal);
            return existing.coal();
        }
        EngineSpot.Spot spot = EngineSpot.find(size.getX(), size.getY(), size.getZ(),
                (x, y, z) -> !level.getBlockState(origin.offset(x, y, z)).isAir(),
                (x, y, z) -> isStoneLike(level, origin.offset(x, y, z)));
        if (spot == null) return -1;
        BlockPos pos = origin.offset(spot.x(), spot.y(), spot.z());
        Direction facing = "south".equals(spot.facing()) ? Direction.SOUTH : Direction.NORTH;
        BlockState state = ModBlocks.ENGINE_BLOCK.get().defaultBlockState()
                .setValue(EngineBlock.FACING, facing)
                .setValue(EngineBlock.LIT, coal > 0);
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
        if (level.getBlockEntity(pos) instanceof EngineBlockEntity engine) {
            engine.setCoal(coal);
            return engine.coal();
        }
        return -1;
    }

    /** A full, plain stone-type block with nothing stored in it — see {@link EngineSpot#isStoneLike}. */
    private static boolean isStoneLike(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.hasBlockEntity() || !state.isCollisionShapeFullBlock(level, pos)) return false;
        return EngineSpot.isStoneLike(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }
}
