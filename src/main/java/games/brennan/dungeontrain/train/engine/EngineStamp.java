package games.brennan.dungeontrain.train.engine;

import games.brennan.dungeontrain.block.engine.EngineBlock;
import games.brennan.dungeontrain.block.engine.EngineBlockEntity;
import games.brennan.dungeontrain.registry.ModBlocks;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.SharedBuild;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import games.brennan.dungeontrain.train.SharedRoomRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Drifting Engine Blocks in placed boxes: finding them, adding up their coal, and setting a fresh one down. Works at whatever
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

    /** A drifting build's box: where its blocks start, and how far they reach. */
    public record Box(BlockPos origin, Vec3i size) {}

    /** The box a drifting carriage or room occupies, or null for any other kind of build. */
    public static Box box(SharedBuild build) {
        if (build instanceof SharedCarriageRegistry.Instance c) return new Box(c.shipyardOrigin, size(c.dims));
        if (build instanceof SharedRoomRegistry.Instance r) return new Box(r.roomOrigin(), r.size);
        return null;
    }

    /**
     * Every engine in the box at {@code origin} of {@code size}, in the order a snapshot lists their cells
     * (x, then y, then z — see {@code CarriageBlockSnapshot}): the order they burn in. Skips {@code except}.
     */
    public static List<EngineBlockEntity> findAll(ServerLevel level, BlockPos origin, Vec3i size, BlockPos except) {
        List<EngineBlockEntity> out = new ArrayList<>();
        for (int x = 0; x < size.getX(); x++) {
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (!pos.equals(except) && level.getBlockState(pos).getBlock() instanceof EngineBlock
                            && level.getBlockEntity(pos) instanceof EngineBlockEntity engine) {
                        out.add(engine);
                    }
                }
            }
        }
        return out;
    }

    /** The first engine in the box at {@code origin} of {@code size}, or null when there is none. */
    public static EngineBlockEntity find(ServerLevel level, BlockPos origin, Vec3i size) {
        List<EngineBlockEntity> all = findAll(level, origin, size, null);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Coal in every engine of {@code build} together, leaving out the one at {@code except} (one being
     * broken); -1 when the build has no box to look in.
     */
    public static int totalCoal(ServerLevel level, SharedBuild build, BlockPos except) {
        Box box = box(build);
        if (box == null) return -1;
        int total = 0;
        for (EngineBlockEntity engine : findAll(level, box.origin(), box.size(), except)) total += engine.coal();
        return DriftEngine.clampCoal(total);
    }

    /**
     * Make sure the box has engines holding {@code coal} between them: those already there, shared out as
     * {@link DriftEngine#share} does, or else a new one in place of a block facing the room, else on a
     * free floor cell ({@link EngineSpot}). Returns the coal the box's engines hold afterwards, or -1 when
     * there was no engine and nowhere to put one.
     */
    public static int ensure(ServerLevel level, BlockPos origin, Vec3i size, int coal) {
        List<EngineBlockEntity> existing = findAll(level, origin, size, null);
        if (!existing.isEmpty()) {
            int[] counts = DriftEngine.share(coal, existing.size());
            int total = 0;
            for (int i = 0; i < counts.length; i++) {
                existing.get(i).setCoal(counts[i]);
                total += existing.get(i).coal();
            }
            return total;
        }
        EngineSpot.Spot spot = EngineSpot.find(size.getX(), size.getY(), size.getZ(),
                (x, y, z) -> !level.getBlockState(origin.offset(x, y, z)).isAir(),
                (x, y, z) -> grade(level, origin.offset(x, y, z)));
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

    /** How readily the engine takes this block's place: only a full block with nothing stored in it, by {@link EngineSpot#grade}. */
    private static int grade(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.hasBlockEntity() || !state.isCollisionShapeFullBlock(level, pos)) return EngineSpot.NONE;
        return EngineSpot.grade(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }
}
