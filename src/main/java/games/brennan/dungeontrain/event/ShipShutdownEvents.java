package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import games.brennan.dungeontrain.ship.Shipyards;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.util.List;

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
 * <p>On {@link ServerStoppingEvent} (which fires before {@code stopServer()}),
 * we delete every DT-managed train (one {@link ManagedShip} per carriage)
 * via {@link Shipyard#delete}. Sable queues each sub-level for removal; we
 * then pump {@link ServerSubLevelContainer#tick} in an unconditional 20-tick
 * loop. A single tick processes one round of changes and is not enough to
 * fully drain several sub-levels removed at once — leftover
 * {@code PlotChunkHolder}s would keep {@code ChunkMap.hasWork()} true and
 * the vanilla shutdown wait loop would spin forever. An earlier revision
 * short-circuited the loop when {@code container.getAllSubLevels()} no
 * longer reported any {@code isRemoved()} entries, but that broke out
 * after 1 tick on the "create world → immediate Save-and-Quit" path and
 * left vanilla spinning on "Saving worlds" — Sable evicts sub-levels from
 * its own list before the chunk-map holders drain. Always running the
 * full budget costs up to ~1 s on every clean shutdown that touches DT
 * sub-levels but provably terminates.
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

    private ShipShutdownEvents() {}

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        long t0 = System.nanoTime();
        int totalDeleted = 0;

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
            // processed before vanilla's shutdown wait loop. A single tick
            // only runs one processChanges() pass and leaves PlotChunkHolders
            // in ChunkMap.updatingChunkMap when several sub-levels are removed
            // at once — vanilla's wait-for-no-work loop then spins forever.
            //
            // No early break: we always run the full {@code fullDrainTicks}.
            // The old break-when-{@code !anyRemoved} heuristic broke out after
            // 1 tick in the "create world → immediate Save-and-Quit" stress
            // path (Sable evicts sub-levels from its own list before the
            // {@code PlotChunkHolder}s drain from {@code ChunkMap.updatingChunkMap}),
            // leaving the vanilla shutdown loop spinning on "Saving worlds".
            // Cost of the brute-force drain: up to {@code fullDrainTicks * 50ms}
            // (~1 s) on every clean shutdown that touches DT sub-levels.
            //
            // TODO(smarter-drain): replace the fixed-tick budget with a
            // condition that directly observes the thing vanilla actually
            // waits on — {@code ChunkMap.hasWork()} (or equivalently, that
            // {@code ChunkMap.updatingChunkMap} contains no {@code PlotChunkHolder}
            // entries for the now-removed plots). Both are private; reaching
            // them needs either an accessor mixin we add or reflection through
            // {@code ServerLevel.getChunkSource().chunkMap}. Worth doing once
            // upstream Sable lands a proper fix and we can compare behaviours;
            // until then the brute-force loop is simpler and provably terminates.
            //   Sable hook point:  {@code dev.ryanhcode.sable.plot.ChunkMapMixin}
            //   Vanilla wait site: {@code net.minecraft.server.MinecraftServer.stopServer}
            //                      → {@code chunkMap.hasWork()} polled by
            //                      {@code waitUntilNextTick} during shutdown
            //   Upstream issue:    https://github.com/ryanhcode/sable/issues/679
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null && deletedHere > 0) {
                final int fullDrainTicks = 20;
                for (int i = 0; i < fullDrainTicks; i++) {
                    container.tick();
                }
                LOGGER.info("[DungeonTrain] Drained {} sub-level removals over {} ticks for {}",
                    deletedHere, fullDrainTicks, level.dimension().location());
            }

            if (deletedHere > 0) {
                LOGGER.info("[DungeonTrain] Shutdown cleanup: deleted {} train sub-levels in {}",
                    deletedHere, level.dimension().location());
            }
            totalDeleted += deletedHere;
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        LOGGER.info("[DungeonTrain] ServerStopping: deleted {} train sub-levels across all levels in {}ms",
            totalDeleted, elapsedMs);
    }
}
