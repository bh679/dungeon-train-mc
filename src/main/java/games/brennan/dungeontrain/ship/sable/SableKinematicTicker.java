package games.brennan.dungeontrain.ship.sable;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.ship.KinematicDriver;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyard;
import games.brennan.dungeontrain.ship.Shipyards;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Per-tick driver: every {@link LevelTickEvent.Post}, for every ship with a
 * kinematic driver attached, query {@link KinematicDriver#nextTransform} and
 * apply via {@link ManagedShip#applyTickOutput}.
 *
 * <p>Why this exists separately from {@code TrainWindowManager}: that class
 * only calls {@code applyTickOutput} inside its rolling-window mutation flow,
 * which doesn't fire for idle trains. Without a per-tick generic ticker,
 * Sable's physics integrator applies gravity each tick and trains fall
 * because no kinematic correction overrides it. This ticker enforces the
 * driver's authority every tick regardless of mutation state.</p>
 *
 * <p>Ordering: TrainWindowManager's compensation pass also writes
 * {@code applyTickOutput} for ships in the rolling window — that's expected.
 * Both listeners run at default {@code @SubscribeEvent} priority on the
 * Post-tick; whichever runs last wins, and for non-mutation ticks they
 * compute the same value so the order is irrelevant.</p>
 *
 * <p>This complements (does not replace) {@code TrainWindowManager.onLevelTick}
 * — that class still owns the compensated-pivot logic for mutation ticks.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SableKinematicTicker {

    private SableKinematicTicker() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        Shipyard shipyard = Shipyards.of(level);
        for (ManagedShip ship : shipyard.findAll()) {
            KinematicDriver driver = ship.getKinematicDriver();
            if (driver == null) continue;
            KinematicDriver.TickOutput output = driver.nextTransform(ship.currentTickInput());
            if (output != null) {
                ship.applyTickOutput(output);
            }
        }
    }
}
