package games.brennan.dungeontrain.advancement.requirement;

import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Forgets the loaded requirements when the server they described stops. The relay fetch and its
 * disk cache are per-JVM and stay; the next server's datapack apply refills the registry.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class AdvancementRequirementLifecycle {

    private AdvancementRequirementLifecycle() {}

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        AdvancementRequirements.clear();
        AdvancementRequirementOverrides.markApplied(null);
    }
}
