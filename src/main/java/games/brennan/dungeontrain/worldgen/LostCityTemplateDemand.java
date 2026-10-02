package games.brennan.dungeontrain.worldgen;

import net.minecraft.resources.ResourceLocation;

/**
 * The demand side of the Lost City template cache: every Lost City start the era veto lets through
 * ({@code mixin/StructureBasementMixin}) is reported here, on whichever thread is generating — a vanilla
 * worldgen worker, a Distant Horizons LOD worker far beyond any player's view, a pregenerator.
 *
 * <p>Player position alone cannot say where chunks are being generated, so the pre-load reads this as well:
 * a start approved while the cache is cold kicks the pre-load at once, with that structure's templates first,
 * and no eviction happens until starts have stopped for {@link LostCityTemplatePreload#DEMAND_HOLD_NANOS}.
 * The hot path is two volatile writes and the listener's own early-out.</p>
 */
public final class LostCityTemplateDemand {

    /** Told of each approved start; must be cheap and must not block (it runs on worldgen threads). */
    @FunctionalInterface
    public interface Listener {
        void requested(ResourceLocation structure, int chunkX);
    }

    private static volatile boolean seen;
    private static volatile long lastNanos;
    private static volatile Listener listener;

    private LostCityTemplateDemand() {}

    public static void setListener(Listener l) {
        listener = l;
    }

    /** A Lost City {@code structure} is about to start in chunk column {@code chunkX}. */
    public static void requested(ResourceLocation structure, int chunkX) {
        lastNanos = System.nanoTime();
        seen = true;
        Listener l = listener;
        if (l != null) l.requested(structure, chunkX);
    }

    /** Whether a start has been approved recently enough to hold the cache, as of {@code nowNanos}. */
    public static boolean holds(long nowNanos) {
        return LostCityTemplatePreload.demandHolds(seen, nowNanos, lastNanos);
    }

    /** Seconds since the last approved start, or {@code -1} if there has been none. */
    public static long secondsSinceLast(long nowNanos) {
        return seen ? (nowNanos - lastNanos) / 1_000_000_000L : -1L;
    }

    public static void reset() {
        seen = false;
    }
}
