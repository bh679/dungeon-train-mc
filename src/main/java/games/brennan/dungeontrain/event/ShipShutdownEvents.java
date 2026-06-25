package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

/**
 * Runs the {@link TrainSubLevelTeardown hardened Sable #679 teardown} at
 * {@link ServerStoppingEvent} (which fires before
 * {@code MinecraftServer.stopServer()}'s wait-for-no-work loop). Without it,
 * leaked sub-level {@code PlotChunkHolder}s keep {@code ChunkMap.hasWork()}
 * {@code true} and the "Saving world…" screen hangs indefinitely.
 *
 * <p>The drain itself — delete trains → bounded pump → reflective
 * {@code PlotChunkHolder} sweep → vanilla-holder drain (after pre-save) — lives
 * in {@link TrainSubLevelTeardown}, shared with the client world-exit pre-drain
 * ({@code DeathScreenLayoutHandler.preDrainTrainSubLevels}) so both paths run
 * identical, proven logic. See that class for the full rationale and safety
 * argument.
 *
 * <p>Common-scope (NOT {@code Dist}-gated) so dedicated servers also benefit —
 * the bug affects every Sable installation.
 *
 * <p>See: <a href="https://github.com/ryanhcode/sable/issues/679">Sable issue
 * #679</a>.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class ShipShutdownEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Wall-clock budget per level for the best-effort Sable container pump. */
    private static final long PUMP_NANOS = 1_000_000_000L; // 1 s

    private ShipShutdownEvents() {}

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        long t0 = System.nanoTime();
        int totalDeleted = 0;
        int totalPlotSwept = 0;
        int totalVanillaSwept = 0;

        for (ServerLevel level : event.getServer().getAllLevels()) {
            TrainSubLevelTeardown.Result r = TrainSubLevelTeardown.teardownLevel(level, PUMP_NANOS);

            if (r.plotSwept() > 0) {
                LOGGER.info("[DungeonTrain] Swept {} leaked PlotChunkHolders from updatingChunkMap in {}",
                    r.plotSwept(), level.dimension().location());
            }
            if (r.vanillaDrained() > 0) {
                LOGGER.info("[DungeonTrain] Drained {} remaining vanilla ChunkHolders from updatingChunkMap in {} (after pre-save)",
                    r.vanillaDrained(), level.dimension().location());
            }
            if (r.deleted() > 0) {
                LOGGER.info("[DungeonTrain] Shutdown cleanup: deleted {} train sub-levels in {}",
                    r.deleted(), level.dimension().location());
            }

            totalDeleted += r.deleted();
            totalPlotSwept += r.plotSwept();
            totalVanillaSwept += r.vanillaDrained();
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        LOGGER.info("[DungeonTrain] ServerStopping: deleted {} train sub-levels, swept {} PlotChunkHolders, drained {} vanilla ChunkHolders across all levels in {}ms",
            totalDeleted, totalPlotSwept, totalVanillaSwept, elapsedMs);
    }
}
