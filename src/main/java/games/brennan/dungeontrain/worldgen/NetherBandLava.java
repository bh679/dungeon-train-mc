package games.brennan.dungeontrain.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;

/**
 * Makes lava inside the Nether band's core flow exactly as it does in the real Nether.
 *
 * <p>The band is Nether terrain that still lives in the <b>overworld</b> dimension, so
 * {@code level.dimensionType().ultraWarm()} is {@code false} there — and vanilla {@code LavaFluid}
 * keys all three of its flow properties off that one flag:</p>
 *
 * <table>
 *   <tr><th>property</th><th>overworld</th><th>ultraWarm</th><th>effect</th></tr>
 *   <tr><td>{@code getTickDelay}</td><td>30</td><td>10</td><td>how fast a flow advances</td></tr>
 *   <tr><td>{@code getDropOff}</td><td>2</td><td>1</td><td>spread reach: 3 blocks → 7 blocks</td></tr>
 *   <tr><td>{@code getSlopeFindDistance}</td><td>2</td><td>4</td><td>how far ahead it looks for a hole</td></tr>
 * </table>
 *
 * <p>Those three methods take only a {@link LevelReader} — no position — so they can't be gated on
 * world-X by overriding them. Every <em>call site</em> does have a {@link BlockPos} in scope though,
 * so the mixins ({@code LavaFluidNetherBandMixin}, {@code LiquidBlockNetherBandLavaMixin},
 * {@code FlowingFluidNetherBandLavaMixin}) modify each call's result with the position in hand and
 * funnel the decision through this one helper.</p>
 *
 * <p>Gated on {@link NetherBand#isInNetherBiome} — the same core predicate the band's other
 * Nether-dimension behaviours use (no lightning, wet-sponge drying, no piglin zombification; see
 * {@code NetherBandBehaviourEvents}), so "where lava is Nether lava" and "where the world reads as
 * the Nether" stay one notion. A lava body straddling the core edge therefore flows Nether-fast on
 * the inside and vanilla-slow on the outside; that discontinuity is accepted.</p>
 */
public final class NetherBandLava {

    /** Vanilla ultraWarm values — see {@code LavaFluid}. */
    private static final int NETHER_TICK_DELAY = 10;
    private static final int NETHER_DROP_OFF = 1;
    private static final int NETHER_SLOPE_FIND_DISTANCE = 4;

    private NetherBandLava() {}

    /**
     * True when lava at {@code pos} should behave as Nether lava. Cheap fast-outs first (server +
     * overworld), then {@link NetherBand#isInNetherBiome}, which itself falls out immediately when
     * the nether phase is disabled or the world has no train.
     */
    public static boolean isNetherLava(LevelReader level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) return false;   // spreading is server-driven
        if (!server.dimension().equals(Level.OVERWORLD)) return false;
        return NetherBand.isInNetherBiome(server, pos.getX());
    }

    /** Nether tick delay (10) inside the core, else the vanilla value. */
    public static int tickDelay(int vanilla, LevelReader level, BlockPos pos) {
        return isNetherLava(level, pos) ? NETHER_TICK_DELAY : vanilla;
    }

    /** Nether drop-off (1 → 7-block reach) inside the core, else the vanilla value. */
    public static int dropOff(int vanilla, LevelReader level, BlockPos pos) {
        return isNetherLava(level, pos) ? NETHER_DROP_OFF : vanilla;
    }

    /** Nether slope-find distance (4) inside the core, else the vanilla value. */
    public static int slopeFindDistance(int vanilla, LevelReader level, BlockPos pos) {
        return isNetherLava(level, pos) ? NETHER_SLOPE_FIND_DISTANCE : vanilla;
    }
}
