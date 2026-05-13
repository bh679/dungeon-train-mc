package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
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
 * then pump {@link ServerSubLevelContainer#tick} in a bounded loop until no
 * {@link ServerSubLevel} reports {@code isRemoved()} (or a safety cap of 20
 * ticks fires). A single tick processes one round of changes and is not
 * enough to fully drain several sub-levels removed at once — leftover
 * {@code PlotChunkHolder}s would keep {@code ChunkMap.hasWork()} true and
 * the vanilla shutdown wait loop would spin forever.
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
            // Drain in a bounded loop until no removed sub-levels remain.
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null && deletedHere > 0) {
                final int maxTicks = 20;
                int ticksUsed = 0;
                for (int i = 0; i < maxTicks; i++) {
                    container.tick();
                    ticksUsed++;
                    boolean anyRemoved = container.getAllSubLevels().stream()
                        .anyMatch(ServerSubLevel::isRemoved);
                    if (!anyRemoved) break;
                }
                if (ticksUsed >= maxTicks) {
                    LOGGER.warn("[DungeonTrain] Drain loop hit cap of {} ticks in {} — sub-levels may still be pending removal",
                        maxTicks, level.dimension().location());
                } else {
                    LOGGER.info("[DungeonTrain] Drained {} sub-level removals in {} ticks for {}",
                        deletedHere, ticksUsed, level.dimension().location());
                }
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
