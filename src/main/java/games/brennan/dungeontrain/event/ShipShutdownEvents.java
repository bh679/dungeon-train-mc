package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Workaround for an upstream Sable bug where
 * {@link net.minecraft.server.MinecraftServer#stopServer}'s wait-for-no-work
 * loop never exits while sub-level {@code PlotChunkHolder}s are present in
 * {@code ChunkMap.updatingChunkMap}. Sable's {@code plot.ChunkMapMixin}
 * attempts to filter them out via {@code @Redirect} on
 * {@code Long2ObjectLinkedOpenHashMap.isEmpty()} but its own javadoc admits
 * the fix is incomplete ("TODO: Remove when plot chunks are unloaded with
 * their plots"). The user-visible symptom is the "Saving world…" screen
 * hanging indefinitely after clicking Save and Quit.
 *
 * <p>Diagnostic evidence (captured 2026-05-18 via {@link ShutdownDiagnostics})
 * on a fresh DT world with no train interaction at all: 1086 chunks in
 * {@code updatingChunkMap} at {@code ServerStopping}, 176 drained in 2 s,
 * then 910 leaked {@code PlotChunkHolder}s stalled for 58+ seconds with the
 * server thread spinning RUNNABLE in {@code ChunkMap.processUnloads}. Vanilla
 * never recovers — the user has to kill the JVM.
 *
 * <p>On {@link ServerStoppingEvent} (which fires before {@code stopServer()})
 * we do four things, in order:
 * <ol>
 *   <li>Delete every DT-managed train (one {@link ManagedShip} per carriage)
 *       via {@link Shipyard#delete}, queueing each sub-level for Sable
 *       removal.</li>
 *   <li>Pump {@link ServerSubLevelContainer#tick} for ~1 s wall-clock so
 *       Sable's own cleanup can process the markRemoved() calls above.
 *       This handles the well-behaved path.</li>
 *   <li>Sweep {@code ChunkMap.updatingChunkMap} reflectively, removing any
 *       remaining entries whose class is exactly Sable's
 *       {@code PlotChunkHolder}. These are the leaked holders the Sable
 *       mixin filter missed.</li>
 *   <li>Drain any <em>remaining</em> vanilla {@link ChunkHolder}s from
 *       {@code updatingChunkMap}, but only after invoking
 *       {@link ChunkMap#saveAllChunks(boolean)} reflectively to flush
 *       their persistent block/entity state to disk. Vanilla overworld
 *       chunks with non-clearable tickets ({@code FORCED}, {@code START},
 *       mod tickets) otherwise pin the wait loop. See
 *       {@link #drainRemainingChunkHolders} for the diagnostic that
 *       captured this scenario.</li>
 * </ol>
 *
 * <p>Together, steps 3 and 4 ensure {@code hasWork()} returns {@code false}
 * before vanilla's wait loop iterates, letting shutdown proceed cleanly.
 *
 * <p>Why the sweep is safe:
 * <ul>
 *   <li>Same thread, no concurrency — runs inside the {@code ServerStoppingEvent}
 *       handler on the Server thread, before vanilla's wait loop starts.</li>
 *   <li>Class-name match is exact ({@value #PLOT_CHUNK_HOLDER_CLASS}); vanilla
 *       {@code ChunkHolder} and any other-mod subclass are untouched.</li>
 *   <li>Reflection wrapped in try/catch — failures degrade to a single warn
 *       log; sweep no-ops, same broken behaviour as before the fix.</li>
 * </ul>
 *
 * <p>Acceptable side effects:
 * <ul>
 *   <li>Trains are not persisted across reload anyway —
 *       {@link TrainBootstrapEvents} auto-spawns a fresh starter train at
 *       every {@code ServerStartedEvent}, so the user sees a working train
 *       on next world load.</li>
 *   <li>Carriage variants, contents, and templates are persisted to NBT/
 *       config files separately — only the kinematic ship state is lost,
 *       which is regenerated.</li>
 *   <li>Players riding carriages get teleported back to a corridor-side
 *       spawn on next login by {@link PlayerJoinEvents}.</li>
 *   <li>Vanilla chunk data (blocks, entities) is persisted via the
 *       pre-drain {@code saveAllChunks(true)} call, so block changes made
 *       up to the moment of shutdown survive the drain.</li>
 * </ul>
 *
 * <p>Common-scope (NOT {@code Dist}-gated) so dedicated servers also benefit
 * — the bug affects every Sable installation, not just integrated servers.
 *
 * <p>See: <a href="https://github.com/ryanhcode/sable/issues/679">Sable issue
 * #679</a> for the upstream bug.
 */
public final class ShipShutdownEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String PLOT_CHUNK_HOLDER_CLASS = "dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder";
    private static Field updatingChunkMapField;

    private ShipShutdownEvents() {}

        public static void onServerStopping(net.minecraft.server.MinecraftServer server) {
        long t0 = System.nanoTime();
        int totalDeleted = 0;
        int totalPlotSwept = 0;
        int totalVanillaSwept = 0;

        for (ServerLevel level : server.getAllLevels()) {
            Shipyard shipyard = Shipyards.of(level);
            List<ManagedShip> ships = shipyard.findAll();
            int deletedHere = 0;
            for (ManagedShip ship : ships) {
                if (ship.getKinematicDriver() instanceof TrainTransformProvider) {
                    shipyard.delete(ship);
                    deletedHere++;
                }
            }

            // Pump Sable's container so the markRemoved() calls above are
            // processed on Sable's side. ~1 s wall-clock with Thread.yield()
            // gives Sable's background cleanup threads CPU time. This handles
            // the well-behaved path; any PlotChunkHolders that leak past it
            // are caught by the sweep below.
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null) {
                final long drainNanos = 1_000_000_000L;
                long deadline = System.nanoTime() + drainNanos;
                while (System.nanoTime() < deadline) {
                    container.tick();
                    Thread.yield();
                }
            }

            int plotSweptHere = sweepLeakedPlotChunkHolders(level);
            if (plotSweptHere > 0) {
                LOGGER.info("[DungeonTrain] Swept {} leaked PlotChunkHolders from updatingChunkMap in {}",
                    plotSweptHere, level.dimension().location());
            }
            totalPlotSwept += plotSweptHere;

            int vanillaSweptHere = drainRemainingChunkHolders(level);
            if (vanillaSweptHere > 0) {
                LOGGER.info("[DungeonTrain] Drained {} remaining vanilla ChunkHolders from updatingChunkMap in {} (after pre-save)",
                    vanillaSweptHere, level.dimension().location());
            }
            totalVanillaSwept += vanillaSweptHere;

            if (deletedHere > 0) {
                LOGGER.info("[DungeonTrain] Shutdown cleanup: deleted {} train sub-levels in {}",
                    deletedHere, level.dimension().location());
            }
            totalDeleted += deletedHere;
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        LOGGER.info("[DungeonTrain] ServerStopping: deleted {} train sub-levels, swept {} PlotChunkHolders, drained {} vanilla ChunkHolders across all levels in {}ms",
            totalDeleted, totalPlotSwept, totalVanillaSwept, elapsedMs);
    }

    private static int sweepLeakedPlotChunkHolders(ServerLevel level) {
        try {
            if (updatingChunkMapField == null) {
                updatingChunkMapField = ChunkMap.class.getDeclaredField("updatingChunkMap");
                updatingChunkMapField.setAccessible(true);
            }
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            Object raw = updatingChunkMapField.get(chunkMap);
            if (!(raw instanceof Map<?, ?>)) return 0;
            @SuppressWarnings("unchecked")
            Map<Long, ChunkHolder> map = (Map<Long, ChunkHolder>) raw;
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
     * {@code updatingChunkMap} after the PlotChunkHolder sweep above.
     *
     * <p>Observed scenario (2026-05-19, fresh world with 300+ overworld
     * chunks generated): {@code updatingChunkMap=2094} at
     * {@code ServerStopping}; vanilla drained 303 in the first ~2 s of its
     * wait loop, then stalled at {@code updatingChunkMap=1791} for 58+
     * seconds with {@code pendingUnloads=0} the whole time and the Server
     * thread {@code RUNNABLE} in {@code ChunkMap.processUnloads}. Same shape
     * as the Sable {@code PlotChunkHolder} hang but for vanilla holders —
     * these chunks hold tickets ({@code FORCED}, {@code START}, mod-added)
     * that {@code removeTicketsOnClosing()} doesn't clear, so they never
     * land in {@code toDrop}.
     *
     * <p>Safety: vanilla {@code ChunkHolder}s carry persistent player data
     * (block changes, entity state). Yanking them without saving would risk
     * data loss. Mitigated by calling
     * {@link net.minecraft.server.level.ServerChunkCache#save(boolean)}
     * with {@code flush=true} immediately before the drain. That public API
     * runs {@code runDistanceManagerUpdates()} (benign during shutdown) and
     * then iterates {@code visibleChunkMap} synchronously, flushing the
     * I/O worker before returning. {@code visibleChunkMap} holds the same
     * {@code ChunkHolder} instances as {@code updatingChunkMap}, so the
     * on-disk state matches what we're about to clear.
     *
     * <p>After the drain, {@code hasWork()} returns {@code false} because
     * {@code updatingChunkMap.isEmpty()} is now true. Vanilla's wait loop
     * exits on its next iteration and shutdown proceeds normally to
     * {@code level.close()}.
     *
     * <p>If the pre-save throws, we still drain — chunks in
     * {@code visibleChunkMap} are likely already on disk from the most
     * recent autosave tick, and a hung world is worse than slightly stale
     * data. The warn log captures the failure for follow-up.
     */
    private static int drainRemainingChunkHolders(ServerLevel level) {
        try {
            if (updatingChunkMapField == null) {
                updatingChunkMapField = ChunkMap.class.getDeclaredField("updatingChunkMap");
                updatingChunkMapField.setAccessible(true);
            }
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            Object raw = updatingChunkMapField.get(chunkMap);
            if (!(raw instanceof Map<?, ?>)) return 0;
            @SuppressWarnings("unchecked")
            Map<Long, ChunkHolder> map = (Map<Long, ChunkHolder>) raw;
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
}
