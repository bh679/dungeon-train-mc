package games.brennan.dungeontrain.worldgen.legacy.beta;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;

/**
 * A {@link WorldGenLevel} seen through Beta coordinates ({@code y = 0..127}, shifted by
 * the band's {@linkplain games.brennan.dungeontrain.worldgen.legacy.LegacyBands#yOffset Y offset} into the world) with the few material questions the Beta decorators
 * ask. Reads outside Beta's height answer air, writes outside it are dropped — as Beta's own bounds did.
 * An optional horizontal offset maps Beta X/Z onto the world ({@code world = beta − offset}) for bands that
 * read Beta terrain from elsewhere (the Far Lands). Not thread-safe (one mutable cursor); create one per
 * decoration call.
 */
public final class BetaWorld {

    private final WorldGenLevel level;
    private final int yOffset;
    private final int height;
    private final int offsetX;
    private final int offsetZ;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    /** {@code yOffset}: world Y of the old {@code y = 0}; Beta's 128-block height. */
    public BetaWorld(WorldGenLevel level, int yOffset) {
        this(level, yOffset, BetaTerrain.HEIGHT);
    }

    /** An old world {@code height} blocks tall (Indev floating levels are 256) at {@code yOffset}. */
    public BetaWorld(WorldGenLevel level, int yOffset, int height) {
        this(level, yOffset, height, 0, 0);
    }

    private BetaWorld(WorldGenLevel level, int yOffset, int height, int offsetX, int offsetZ) {
        this.level = level;
        this.yOffset = yOffset;
        this.height = height;
        this.offsetX = offsetX;
        this.offsetZ = offsetZ;
    }

    /**
     * A Beta-height view whose old coordinate {@code (x, z)} is world {@code (x − offsetX, z − offsetZ)} —
     * for a band that decorates Beta terrain read from elsewhere (the Far Lands).
     */
    public static BetaWorld shifted(WorldGenLevel level, int yOffset, int offsetX, int offsetZ) {
        return new BetaWorld(level, yOffset, BetaTerrain.HEIGHT, offsetX, offsetZ);
    }

    /** The old world's height ({@code y} runs {@code 0..height-1}). */
    public int height() {
        return height;
    }

    public WorldGenLevel level() {
        return level;
    }

    /** World position of Beta coordinates (fresh, immutable). */
    public BlockPos pos(int x, int y, int z) {
        return new BlockPos(x - offsetX, y + yOffset, z - offsetZ);
    }

    public BlockState get(int x, int y, int z) {
        if (y < 0 || y >= height) return Blocks.AIR.defaultBlockState();
        return level.getBlockState(cursor.set(x - offsetX, y + yOffset, z - offsetZ));
    }

    public void set(int x, int y, int z, BlockState state) {
        if (y < 0 || y >= height) return;
        level.setBlock(cursor.set(x - offsetX, y + yOffset, z - offsetZ), state, Block.UPDATE_CLIENTS);
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
        for (int y = height - 1; y >= 0; y--) {
            BlockState s = get(x, y, z);
            if (!s.isAir() && (s.isSolid() || !s.getFluidState().isEmpty() || s.getBlock() instanceof LeavesBlock)) {
                return y + 1;
            }
        }
        return 0;
    }

    /** Beta's {@code findTopSolidBlock}: one above the highest solid or liquid block. */
    public int topSolidOrLiquid(int x, int z) {
        for (int y = height - 1; y > 0; y--) {
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
