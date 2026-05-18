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
 * we do three things, in order:
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
 *       mixin filter missed — directly removing them lets vanilla's
 *       {@code hasWork()} return false and the wait loop exit.</li>
 * </ol>
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
 * </ul>
 *
 * <p>Common-scope (NOT {@code Dist}-gated) so dedicated servers also benefit
 * — the bug affects every Sable installation, not just integrated servers.
 *
 * <p>See: <a href="https://github.com/ryanhcode/sable/issues/679">Sable issue
 * #679</a> for the upstream bug.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ShipShutdownEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String PLOT_CHUNK_HOLDER_CLASS = "dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder";
    private static Field updatingChunkMapField;

    private ShipShutdownEvents() {}

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        long t0 = System.nanoTime();
        int totalDeleted = 0;
        int totalSwept = 0;

        for (ServerLevel level : event.getServer().getAllLevels()) {
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

            int sweptHere = sweepLeakedPlotChunkHolders(level);
            if (sweptHere > 0) {
                LOGGER.info("[DungeonTrain] Swept {} leaked PlotChunkHolders from updatingChunkMap in {}",
                    sweptHere, level.dimension().location());
            }
            totalSwept += sweptHere;

            if (deletedHere > 0) {
                LOGGER.info("[DungeonTrain] Shutdown cleanup: deleted {} train sub-levels in {}",
                    deletedHere, level.dimension().location());
            }
            totalDeleted += deletedHere;
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        LOGGER.info("[DungeonTrain] ServerStopping: deleted {} train sub-levels, swept {} leaked PlotChunkHolders across all levels in {}ms",
            totalDeleted, totalSwept, elapsedMs);
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
}
