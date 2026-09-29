package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.world.FluidInteractionDeferral;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LiquidBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side glue for {@link FluidInteractionDeferral}: the per-level stores, the guard the mixin
 * calls, and the level-tick replay.
 *
 * <p>{@code FluidInteractionNoLoadMixin} calls {@link #deferIfNeighbourUnloaded} at the head of
 * NeoForge's {@code FluidInteractionRegistry.canInteract}. On {@code LevelTickEvent.Post} every
 * position whose missing chunks have since loaded is re-run through {@code canInteract} — the public
 * entry point {@code LiquidBlock.onPlace} itself uses — provided its own chunk is still loaded and the
 * block is still a liquid. A position may legitimately be deferred again from inside that replay if a
 * <em>different</em> neighbour chunk is now the missing one.</p>
 *
 * <p>Everything here runs on the server thread: {@code canInteract} off-thread (or on the client) is
 * left untouched by the guard, and {@code ServerChunkCache.getChunkNow} is main-thread-only anyway.
 * Stores are dropped on {@code LevelEvent.Unload}.</p>
 *
 * <p>Logs one aggregate {@code [fluid.defer]} line at DEBUG every {@link #LOG_PERIOD_TICKS} ticks
 * while anything has happened, so lag reports (which capture {@code debug.log}) show the guard
 * working instead of a stall stack.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class FluidInteractionDeferralEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger(FluidInteractionDeferralEvents.class);

    /** How often the aggregate counters are logged (10 s of game time). */
    static final int LOG_PERIOD_TICKS = 200;

    /** One store per server level; {@code ServerLevel} uses identity equality. Server thread only. */
    private static final Map<ServerLevel, FluidInteractionDeferral> STORES = new HashMap<>();

    /** Counters as of the last log line, per level, so the line reports the delta. */
    private static final Map<ServerLevel, long[]> LAST_LOGGED = new HashMap<>();

    private FluidInteractionDeferralEvents() {
    }

    /**
     * The mixin's guard. {@code true} means "a face neighbour of {@code pos} is in a chunk that is
     * not loaded; the check has been recorded for replay and the caller should report no interaction".
     * {@code false} means "proceed as vanilla" — interior block, every neighbour chunk loaded, or not
     * on the server thread (where a non-loading lookup cannot be made safely).
     */
    public static boolean deferIfNeighbourUnloaded(final ServerLevel level, final BlockPos pos) {
        final long[] borderChunks = FluidInteractionDeferral.borderNeighbourChunks(pos.getX(), pos.getZ());
        if (borderChunks.length == 0) return false;
        if (!level.getServer().isSameThread()) return false;

        final ServerChunkCache chunks = level.getChunkSource();
        final long[] missing = missingChunks(chunks, borderChunks);
        if (missing.length == 0) return false;

        final FluidInteractionDeferral store = STORES.computeIfAbsent(level, l -> new FluidInteractionDeferral());
        store.defer(pos.asLong(), missing, level.getGameTime());
        return true;
    }

    /** The subset of {@code candidates} that {@code getChunkNow} does not have. */
    private static long[] missingChunks(final ServerChunkCache chunks, final long[] candidates) {
        int count = 0;
        final long[] scratch = new long[candidates.length];
        for (long key : candidates) {
            if (!isLoaded(chunks, key)) scratch[count++] = key;
        }
        return count == candidates.length ? scratch : Arrays.copyOf(scratch, count);
    }

    private static boolean isLoaded(final ServerChunkCache chunks, final long chunkKey) {
        return chunks.getChunkNow(FluidInteractionDeferral.chunkX(chunkKey), FluidInteractionDeferral.chunkZ(chunkKey)) != null;
    }

    @SubscribeEvent
    public static void onLevelTick(final LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        final FluidInteractionDeferral store = STORES.get(level);
        if (store == null) return;

        final ServerChunkCache chunks = level.getChunkSource();
        final List<Long> ready = store.drainReady(level.getGameTime(), key -> isLoaded(chunks, key));
        for (long posLong : ready) {
            replay(level, chunks, BlockPos.of(posLong));
        }
        if (level.getGameTime() % LOG_PERIOD_TICKS == 0) {
            logCounters(level, store);
        }
    }

    /**
     * Re-run the interaction check for a position whose neighbours have arrived. Its own chunk must be
     * loaded too (a non-loading check — this must never become the sync load it replaces), and the
     * block must still be a liquid; otherwise there is nothing to interact.
     */
    private static void replay(final ServerLevel level, final ServerChunkCache chunks, final BlockPos pos) {
        if (chunks.getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) return;
        if (!(level.getBlockState(pos).getBlock() instanceof LiquidBlock)) return;
        FluidInteractionRegistry.canInteract(level, pos);
    }

    private static void logCounters(final ServerLevel level, final FluidInteractionDeferral store) {
        if (!LOGGER.isDebugEnabled()) return;
        final long[] now = {store.deferredTotal(), store.replayedTotal(), store.expiredTotal(), store.droppedTotal()};
        final long[] last = LAST_LOGGED.getOrDefault(level, new long[4]);
        if (Arrays.equals(now, last)) return;
        LOGGER.debug("[fluid.defer] dim={} deferred={} replayed={} expired={} dropped={} pending={} (totals {}/{}/{}/{})",
                level.dimension().location(),
                now[0] - last[0], now[1] - last[1], now[2] - last[2], now[3] - last[3], store.size(),
                now[0], now[1], now[2], now[3]);
        LAST_LOGGED.put(level, now);
    }

    @SubscribeEvent
    public static void onLevelUnload(final LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        STORES.remove(level);
        LAST_LOGGED.remove(level);
    }
}
