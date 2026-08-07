package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.particle.PortalSmokeParticle;
import games.brennan.dungeontrain.registry.ModParticles;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

/**
 * Client half of {@link ModParticles}: what each registered particle type actually draws.
 *
 * <p>Both of DT's types are {@link PortalSmokeParticle}, differing only in the
 * {@link PortalSmokeParticle.Profile} handed to the provider — the wisps that come off the portal
 * corridor's door and the haze that sits in the frame behind them.</p>
 *
 * <p>Client-only and on the mod bus, like {@link ContainerHotkeyClient}: a particle type is
 * registered on both sides (the server has to be able to name it in a packet), but only the client
 * knows how to draw one.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientParticleProviders {

    private ClientParticleProviders() {}

    @SubscribeEvent
    public static void onRegisterProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.PORTAL_SMOKE.get(),
            sprites -> new PortalSmokeParticle.Provider(PortalSmokeParticle.WISP, sprites));
        event.registerSpriteSet(ModParticles.PORTAL_HAZE.get(),
            sprites -> new PortalSmokeParticle.Provider(PortalSmokeParticle.HAZE, sprites));
    }
}
