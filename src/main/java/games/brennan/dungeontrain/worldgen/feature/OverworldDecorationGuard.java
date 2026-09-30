package games.brennan.dungeontrain.worldgen.feature;

import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.DisintegrationBand;
import games.brennan.dungeontrain.worldgen.MixBand;
import games.brennan.dungeontrain.worldgen.NetherBand;
import games.brennan.dungeontrain.worldgen.NetherMountainTerrain;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;

/**
 * Keeps overworld decoration out of the Nether core.
 *
 * <p>The core is stamped at the end of CARVERS ({@link NetherCoreStamp}), so every overworld decoration step
 * runs on top of it: the core's low rows keep overworld cave biomes (lush, dripstone, deep dark, sulfur), and
 * cave features next to the core — lakes, spike and dripstone clusters, sculk — reach across its edge. So while
 * a non-DT feature places ({@link #enter} … {@link #exit}), {@code WorldGenRegionFloorMixin} refuses its writes
 * into core columns ({@link #blocksWrite}). DT's own features — the core's Nether decoration among them — are
 * never scoped.</p>
 *
 * <p>A core column uses the stamp's own test: the band evaluated at the edge-waved X is core, and the End band
 * doesn't own it ({@link #isCoreColumn}).</p>
 */
public final class OverworldDecorationGuard {

    /** How far a feature can write from the chunk it decorates — one chunk (a lake is ±8 from its origin). */
    private static final int FEATURE_REACH = 16;

    /** The core test for the chunk being decorated; {@code null} when no core is within reach. */
    private record Core(WorldGenCycle cycle, long seed, boolean endBandActive) {}

    private static final ThreadLocal<Core> CORE = new ThreadLocal<>();
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private OverworldDecorationGuard() {}

    /**
     * Set up for one chunk's decoration: a core context when a core column is within the chunk's reach,
     * else none (every write passes on one thread-local read). Never throws — worst case the chunk decorates
     * unguarded, as it used to.
     */
    public static void beginChunk(WorldGenLevel level, ChunkPos pos) {
        CORE.set(null);
        try {
            ServerLevel serverLevel = level.getLevel();
            if (!serverLevel.dimension().equals(Level.OVERWORLD)) return;
            ServerLevel overworld = serverLevel.getServer() == null ? null : serverLevel.getServer().overworld();
            if (overworld == null || NetherBand.startX(overworld) == NetherBand.OFF) return;

            WorldGenCycle cycle = MixBand.cycleAt(overworld, pos.x, pos.z);
            int margin = NetherMountainTerrain.maxEdgeShift() + FEATURE_REACH;
            boolean nearCore = false;
            for (int x = pos.getMinBlockX() - margin; x <= pos.getMaxBlockX() + margin && !nearCore; x++) {
                nearCore = cycle.isNetherCore(x);
            }
            if (!nearCore) return;

            boolean endBandActive = DisintegrationBand.startX(overworld) != DisintegrationBand.OFF;
            long seed = DungeonTrainWorldData.get(overworld).getGenerationSeed();
            CORE.set(new Core(cycle, seed, endBandActive));
        } catch (Throwable t) {
            CORE.set(null);
        }
    }

    /** A non-DT feature starts placing. Always pair with {@link #exit} in a {@code finally}. */
    public static void enter() {
        DEPTH.get()[0]++;
    }

    public static void exit() {
        int[] depth = DEPTH.get();
        if (depth[0] > 0) depth[0]--;
    }

    /** True inside a non-DT feature's placement. */
    public static boolean isActive() {
        return DEPTH.get()[0] > 0;
    }

    /** True if the write at {@code (x, z)} must be refused: an overworld feature writing into a core column. */
    public static boolean blocksWrite(int x, int z) {
        Core core = CORE.get();
        if (core == null || !isActive()) return false;
        return isCoreColumn(core.cycle(), core.seed(), core.endBandActive(), x, z);
    }

    /** The {@link NetherCoreStamp} column test — core at the edge-waved X, and not an End-band column. */
    public static boolean isCoreColumn(WorldGenCycle cycle, long seed, boolean endBandActive, int x, int z) {
        int wx = NetherMountainTerrain.wavyX(seed, x, z);
        if (endBandActive && cycle.endMiddleRamp(wx) > 0.0) return false;
        return cycle.isNetherCore(wx);
    }
}
