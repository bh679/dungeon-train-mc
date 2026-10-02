package games.brennan.dungeontrain.client;

import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.ApprovedBuildings;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Tells {@link ApprovedBuildings} how to ask whether this player has allowed network access.
 *
 * <p>The check lives in a client-only class, and {@code ApprovedBuildings} runs on both sides, so the
 * client hands it over at setup rather than have common code reach for a client config.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ApprovedBuildingsClient {

    private ApprovedBuildingsClient() {}

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        ApprovedBuildings.setClientConsent(DiscordPresenceClientConfig::isGranted);
    }
}
