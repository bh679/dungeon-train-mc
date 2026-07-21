package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.tunnel.TunnelGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;

/**
 * Server-side helper for the <b>ocean</b> band — a looping phase of the {@link WorldGenCycle} inserted in
 * the overworld stretch after the upside-down band's trailing gap and before the chuncks band. Across the
 * band's X-range the world is a uniform <b>open sea</b> of {@code minecraft:ocean} whose water surface is
 * raised to the train track bed height ({@link #waterSurfaceY}, not vanilla sea level 63), so the train
 * skims the surface. The sea itself is generated cheaply on the worldgen worker (see
 * {@code NoiseBasedChunkGeneratorMixin}); this class only answers the band-membership, waterline, and
 * fluid-containment queries.
 *
 * <p>Thread-safety mirrors {@link ChuncksBand}: reads only the memoised {@link WorldGenCycle#fromConfig()},
 * the volatile {@link DungeonTrainCommonConfig}, and per-world {@link DungeonTrainWorldData}.</p>
 */
public final class OceanBand {

    /** Returned by {@link #startX} when the ocean band is disabled or the world has no train. */
    public static final long OFF = Long.MAX_VALUE;

    private OceanBand() {}

    /**
     * World-X where the cycle is anchored (shared with the other bands via {@link WorldGenCycle}), or
     * {@link #OFF} when the ocean band is disabled, the world has no train, or the band has no length.
     * Independent of the disintegration/nether/upside-down enable flags.
     */
    public static long startX(ServerLevel overworld) {
        if (!DungeonTrainCommonConfig.isOceanEnabled()) return OFF;
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        if (!data.startsWithTrain()) return OFF;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        if (cycle.period() <= 0L || cycle.oceanLen() <= 0L) return OFF;
        return cycle.startX();
    }

    /** True if the column at {@code worldX} lies in the ocean band. */
    public static boolean isInBand(ServerLevel overworld, int worldX) {
        if (startX(overworld) == OFF) return false;
        return WorldGenCycle.fromConfig().isInOceanBand(worldX);
    }

    /**
     * Raised water-surface Y for the band — the train track bed height ({@link TrackGeometry#bedY()}).
     * Clamped to the bed on purpose (never above it), so the corridor interior — which starts at
     * {@code bedY+1} — stays dry by construction whatever the train Y is. Water fills up to
     * {@code waterSurfaceY - 1} (the waterline sits at bed level).
     */
    public static int waterSurfaceY(ServerLevel overworld) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        return TrackGeometry.from(data.dims(), data.getTrainY()).bedY();
    }

    /**
     * Fluid-containment query for {@code FlowingFluidOceanMixin}: {@code true} if a fluid spread from
     * {@code (fromX, fromY)} to {@code (toX, toY, toZ)} must be <b>vetoed</b> to keep the raised sea
     * static and the corridor dry. Freezes the slab at its three open faces:
     *
     * <ul>
     *   <li><b>Corridor</b> — the destination is an in-band cell inside the corridor Z-span (below the
     *       surface): keeps a dry channel for the train through the sea.</li>
     *   <li><b>Longitudinal edge</b> — the source is in-band raised water and the destination leaves the
     *       band (into the plain-overworld gap / a band-edge coastline), where terrain only reaches
     *       vanilla sea level.</li>
     *   <li><b>Above surface</b> — the source is in-band raised water and the destination is at/above the
     *       water surface (no climbing above the intended waterline).</li>
     * </ul>
     *
     * <p>Cheap-outs first: a spread with neither endpoint in the band (the overwhelming majority) returns
     * {@code false} before any {@link DungeonTrainWorldData} lookup.</p>
     */
    public static boolean vetoSpread(ServerLevel overworld, int fromX, int fromY, int toX, int toY, int toZ) {
        if (startX(overworld) == OFF) return false;
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        boolean toInBand = cycle.isInOceanBand(toX);
        boolean fromInBand = cycle.isInOceanBand(fromX);
        if (!toInBand && !fromInBand) return false;                 // fully outside the band → vanilla

        DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
        TrackGeometry g = TrackGeometry.from(data.dims(), data.getTrainY());
        int surface = g.bedY();

        // Corridor dryness: never let water into the corridor Z-span (wall to wall) below the surface.
        if (toInBand && toY < surface) {
            TunnelGeometry tg = TunnelGeometry.from(g);
            if (toZ >= tg.wallMinZ() && toZ <= tg.wallMaxZ()) return true;
        }
        // Slab containment: band raised water may not escape the band, nor climb above the surface.
        if (fromInBand && fromY < surface) {
            if (!toInBand) return true;                             // longitudinal edge leak
            if (toY >= surface) return true;                        // climbing above the waterline
        }
        return false;
    }
}
