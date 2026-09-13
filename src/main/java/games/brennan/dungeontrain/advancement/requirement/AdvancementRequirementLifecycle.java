package games.brennan.dungeontrain.advancement.requirement;

import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Forgets the loaded requirements when the server they described stops, and retries a failed
 * relay fetch when one starts. The fetch and its disk cache are per-JVM and stay across servers;
 * the next datapack apply refills the registry.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class AdvancementRequirementLifecycle {

    private AdvancementRequirementLifecycle() {}

    /**
     * A dedicated server often boots before its network does, and the first fetch (fired at mod
     * construction) can fail for that alone. A failed attempt is retried here — a success is not
     * refetched — and a late-landing set that differs from what this server applied triggers the
     * one reload {@link AdvancementRequirementOverrides} does.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        AdvancementRequirementOverrides.ensureFetched();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        AdvancementRequirements.clear();
        AdvancementRequirementOverrides.markApplied(null);
    }
}
