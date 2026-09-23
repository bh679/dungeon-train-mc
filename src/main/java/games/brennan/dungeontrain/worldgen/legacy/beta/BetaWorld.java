package games.brennan.dungeontrain.worldgen.legacy.beta;

import games.brennan.dungeontrain.worldgen.legacy.LegacyChunkWriter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;

/**
 * A {@link WorldGenLevel} seen through Beta coordinates ({@code y = 0..127}, shifted by
 * {@link LegacyChunkWriter#Y_OFFSET} into the world) with the few material questions the Beta decorators
 * ask. Reads outside Beta's height answer air, writes outside it are dropped — as Beta's own bounds did.
 * Not thread-safe (one mutable cursor); create one per decoration call.
 */
public final class BetaWorld {

    private final WorldGenLevel level;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public BetaWorld(WorldGenLevel level) {
        this.level = level;
    }

    public WorldGenLevel level() {
        return level;
    }

    /** World position of Beta coordinates (fresh, immutable). */
    public BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y + LegacyChunkWriter.Y_OFFSET, z);
    }

    public BlockState get(int x, int y, int z) {
        if (y < 0 || y >= BetaTerrain.HEIGHT) return Blocks.AIR.defaultBlockState();
        return level.getBlockState(cursor.set(x, y + LegacyChunkWriter.Y_OFFSET, z));
    }

    public void set(int x, int y, int z, BlockState state) {
        if (y < 0 || y >= BetaTerrain.HEIGHT) return;
        level.setBlock(cursor.set(x, y + LegacyChunkWriter.Y_OFFSET, z), state, Block.UPDATE_CLIENTS);
    }

    public boolean isAir(int x, int y, int z) {
        return get(x, y, z).isAir();
    }

    public boolean is(int x, int y, int z, Block block) {
        return get(x, y, z).is(block);
    }

    /** Beta's {@code Material.isSolid()}: anything with a collision body — stone, dirt, logs, leaves. */
    public boolean isSolid(int x, int y, int z) {
        return get(x, y, z).isSolid();
    }

    public boolean isLiquid(int x, int y, int z) {
        return !get(x, y, z).getFluidState().isEmpty();
    }

    /** Beta's {@code opaqueCubeLookup}: full, light-blocking cubes (leaves and plants are not). */
    public boolean isOpaque(int x, int y, int z) {
        return get(x, y, z).canOcclude();
    }

    public boolean isLeaves(int x, int y, int z) {
        return get(x, y, z).getBlock() instanceof LeavesBlock;
    }

    public boolean isAirOrLeaves(int x, int y, int z) {
        BlockState s = get(x, y, z);
        return s.isAir() || s.getBlock() instanceof LeavesBlock;
    }

    /** Beta's {@code getHeightValue}: one above the highest block that blocks light (leaves and water count). */
    public int heightValue(int x, int z) {
        for (int y = BetaTerrain.HEIGHT - 1; y >= 0; y--) {
            BlockState s = get(x, y, z);
            if (!s.isAir() && (s.isSolid() || !s.getFluidState().isEmpty() || s.getBlock() instanceof LeavesBlock)) {
                return y + 1;
            }
        }
        return 0;
    }

    /** Beta's {@code findTopSolidBlock}: one above the highest solid or liquid block. */
    public int topSolidOrLiquid(int x, int z) {
        for (int y = BetaTerrain.HEIGHT - 1; y > 0; y--) {
            BlockState s = get(x, y, z);
            if (s.isSolid() || !s.getFluidState().isEmpty()) return y + 1;
        }
        return -1;
    }

    /** Schedule an immediate fluid tick so a placed spring starts flowing. */
    public void tickFluid(int x, int y, int z, Fluid fluid) {
        level.scheduleTick(pos(x, y, z), fluid, 0);
    }
}
