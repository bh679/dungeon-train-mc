package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Shared hardened teardown for Dungeon Train Sable sub-levels — the workaround
 * for upstream Sable issue #679, where
 * {@link net.minecraft.server.MinecraftServer#stopServer}'s wait-for-no-work
 * loop never exits while sub-level {@code PlotChunkHolder}s linger in
 * {@code ChunkMap.updatingChunkMap}. Sable's {@code plot.ChunkMapMixin} tries to
 * filter them out but its own javadoc admits the fix is incomplete; the
 * user-visible symptom is the "Saving world…" / world-exit transition hanging
 * indefinitely.
 *
 * <p>This logic was originally inline in {@link ShipShutdownEvents} (which runs
 * it at {@link net.neoforged.neoforge.event.server.ServerStoppingEvent}). It is
 * extracted here so the client-side world-exit pre-drain
 * ({@code DeathScreenLayoutHandler.preDrainTrainSubLevels}) runs the
 * <em>identical, proven</em> drain rather than an open-coded busy-spin that
 * never evicted the leaked holders — the cause of the ~2.5-minute freeze when
 * leaving a world after teleporting far along the train corridor.</p>
 *
 * <p>{@link #teardownLevel} does, in order:
 * <ol>
 *   <li>Delete every DT-managed train (one {@link ManagedShip} per carriage)
 *       via {@link Shipyard#delete}, queueing each sub-level for Sable removal.</li>
 *   <li>Pump {@link ServerSubLevelContainer#tick} for up to {@code pumpNanos}
 *       wall-clock so Sable's own cleanup can process the {@code markRemoved()}
 *       calls. This is best-effort: callers pass a small budget (or 0) and rely
 *       on the sweep below to guarantee progress.</li>
 *   <li>Sweep {@code ChunkMap.updatingChunkMap} reflectively, removing any
 *       remaining entries whose class is exactly Sable's
 *       {@code PlotChunkHolder} — the leaked holders the Sable filter missed.</li>
 *   <li>Drain any <em>remaining</em> vanilla {@link ChunkHolder}s from
 *       {@code updatingChunkMap}, after a {@code save(flush=true)} so their
 *       persistent block/entity state is flushed first. Vanilla chunks with
 *       non-clearable tickets ({@code FORCED}, {@code START}, mod tickets)
 *       otherwise pin the wait loop.</li>
 * </ol>
 *
 * <p>Steps 3 and 4 ensure {@code hasWork()} returns {@code false} before
 * vanilla's wait loop iterates, letting teardown proceed. The sweep/drain are
 * O(map size) and do not depend on the pump completing — so even with
 * {@code pumpNanos == 0} the integrated server cannot spin on {@code hasWork()}.</p>
 *
 * <p>Why the reflective sweep is safe:
 * <ul>
 *   <li>Same thread, no concurrency — callers run it on the Server thread
 *       (the {@code ServerStopping} handler, or a {@code server.execute(...)}
 *       task), before vanilla's wait loop starts.</li>
 *   <li>Class-name match is exact ({@value #PLOT_CHUNK_HOLDER_CLASS}); vanilla
 *       {@code ChunkHolder} and other-mod subclasses are untouched.</li>
 *   <li>Reflection wrapped in try/catch — failures degrade to a single warn
 *       log; the method no-ops, same behaviour as before the workaround.</li>
 * </ul>
 *
 * <p>Acceptable side effects (same as the original {@code ServerStopping}
 * path): swept train sub-levels are not Sable-persisted, but trains are
 * regenerated on next load by {@link TrainBootstrapEvents}; carriage variants,
 * contents, and templates persist separately; vanilla chunk data is flushed by
 * the pre-drain {@code save(flush=true)} so block changes survive.</p>
 *
 * <p>Common-scope (NOT {@code Dist}-gated) — the bug affects integrated and
 * dedicated servers alike.</p>
 *
 * <p>See: <a href="https://github.com/ryanhcode/sable/issues/679">Sable issue
 * #679</a>.
 */
public final class TrainSubLevelTeardown {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String PLOT_CHUNK_HOLDER_CLASS = "dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder";
    private static Field updatingChunkMapField;

    private TrainSubLevelTeardown() {}

    /** Outcome of a single-level teardown. */
    public record Result(int deleted, int plotSwept, int vanillaDrained) {}

    /**
     * Delete every DT-managed train (each {@link ManagedShip} whose kinematic
     * driver is a {@link TrainTransformProvider}) in {@code level}, queueing each
     * sub-level for Sable removal. Returns the number deleted.
     */
    public static int deleteTrainShips(ServerLevel level) {
        Shipyard shipyard = Shipyards.of(level);
        List<ManagedShip> ships = shipyard.findAll();
        int deleted = 0;
        for (ManagedShip ship : ships) {
            if (ship.getKinematicDriver() instanceof TrainTransformProvider) {
                shipyard.delete(ship);
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * Full hardened teardown for one level: delete trains → bounded pump →
     * sweep leaked {@code PlotChunkHolder}s → drain remaining vanilla holders
     * (after a pre-save). The pump is capped at {@code pumpNanos} wall-clock
     * (pass {@code 0} to skip it entirely and rely on the sweep). Callers are
     * expected to log the returned {@link Result}.
     */
    public static Result teardownLevel(ServerLevel level, long pumpNanos) {
        int deleted = deleteTrainShips(level);
        pumpContainer(level, pumpNanos);
        int plotSwept = sweepLeakedPlotChunkHolders(level);
        int vanillaDrained = drainRemainingChunkHolders(level);
        return new Result(deleted, plotSwept, vanillaDrained);
    }

    /**
     * Pump Sable's {@link ServerSubLevelContainer} for up to {@code pumpNanos}
     * wall-clock so the {@code markRemoved()} calls from {@link #deleteTrainShips}
     * are processed on Sable's side. {@link Thread#yield()} between ticks gives
     * Sable's background cleanup threads CPU time. Best-effort: any holders that
     * leak past it are caught by {@link #sweepLeakedPlotChunkHolders}.
     */
    private static void pumpContainer(ServerLevel level, long pumpNanos) {
        if (pumpNanos <= 0L) return;
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        long deadline = System.nanoTime() + pumpNanos;
        while (System.nanoTime() < deadline) {
            container.tick();
            Thread.yield();
        }
    }

    private static int sweepLeakedPlotChunkHolders(ServerLevel level) {
        try {
            Map<Long, ChunkHolder> map = updatingChunkMap(level);
            if (map == null) return 0;
            int removed = 0;
            Iterator<Map.Entry<Long, ChunkHolder>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                ChunkHolder holder = it.next().getValue();
                if (holder != null && PLOT_CHUNK_HOLDER_CLASS.equals(holder.getClass().getName())) {
                    it.remove();
                    removed++;
                }
            }
            return removed;
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] PlotChunkHolder sweep failed (non-fatal — vanilla wait loop may still hang)", t);
            return 0;
        }
    }

    /**
     * Second-pass drain for any vanilla {@link ChunkHolder}s that remain in
     * {@code updatingChunkMap} after the PlotChunkHolder sweep — chunks pinned
     * by tickets ({@code FORCED}, {@code START}, mod-added) that
     * {@code removeTicketsOnClosing()} doesn't clear. Calls
     * {@link net.minecraft.server.level.ServerChunkCache#save(boolean)} with
     * {@code flush=true} first so on-disk state matches what we clear, then
     * empties the map so {@code hasWork()} returns {@code false}.
     */
    private static int drainRemainingChunkHolders(ServerLevel level) {
        try {
            Map<Long, ChunkHolder> map = updatingChunkMap(level);
            if (map == null) return 0;
            int remaining = map.size();
            if (remaining == 0) return 0;

            preSave(level);

            map.clear();
            return remaining;
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Vanilla ChunkHolder drain failed (non-fatal — vanilla wait loop may still hang)", t);
            return 0;
        }
    }

    private static void preSave(ServerLevel level) {
        try {
            long t0 = System.nanoTime();
            level.getChunkSource().save(true);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            LOGGER.info("[DungeonTrain] Pre-drain save(flush=true) on {} took {}ms",
                level.dimension().location(), elapsedMs);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Pre-drain save failed on {} — proceeding with drain anyway",
                level.dimension().location(), t);
        }
    }

    /**
     * Reflectively resolve {@code ChunkMap.updatingChunkMap} for {@code level}.
     * Returns {@code null} if the field can't be read or isn't a {@link Map}.
     */
    @SuppressWarnings("unchecked")
    private static Map<Long, ChunkHolder> updatingChunkMap(ServerLevel level) throws Exception {
        if (updatingChunkMapField == null) {
            updatingChunkMapField = ChunkMap.class.getDeclaredField("updatingChunkMap");
            updatingChunkMapField.setAccessible(true);
        }
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        Object raw = updatingChunkMapField.get(chunkMap);
        return raw instanceof Map<?, ?> ? (Map<Long, ChunkHolder>) raw : null;
    }
}
