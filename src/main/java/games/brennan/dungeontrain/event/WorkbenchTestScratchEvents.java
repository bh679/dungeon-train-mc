package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.workbench.WorkbenchTestScratch;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Keeps {@link WorkbenchTestScratch} honest: once a second it takes down any Workbench build no test
 * stands on any more, and at server start and stop it deletes the scratch folder outright — a crash
 * mid-test is the only way one survives.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WorkbenchTestScratchEvents {

    private static final int SWEEP_EVERY_TICKS = 20;

    private static int ticks;

    private WorkbenchTestScratchEvents() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++ticks < SWEEP_EVERY_TICKS) return;
        ticks = 0;
        WorkbenchTestScratch.sweep();
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        WorkbenchTestScratch.wipe();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        WorkbenchTestScratch.wipe();
    }
}
