package games.brennan.dungeontrain.client.localization;
import games.brennan.dungeontrain.DtCore;

import games.brennan.dungeontrain.platform.event.DtReloadListenerRegistrar;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/**
 * NeoForge registration seam for {@link LocalizationCreditRegistry}. Registers
 * {@link LocalizationCreditRegistry#load} as a <b>client</b>-resource-manager
 * reload listener on {@code RegisterClientReloadListenersEvent}, which fires
 * once at {@code Minecraft} construction and again on every resource-pack
 * reload — so credits are populated well before the title screen first
 * renders and update live if the player toggles a localization pack from the
 * title screen's own Options menu.
 *
 * <p>Deliberately the client resource channel, not the server datapack
 * channel {@code NarrativeDataLoaders} uses — the main menu has no
 * world/server running yet, so only resource packs are active there.</p>
 */
public final class LocalizationCreditsClientLoaders {

    private LocalizationCreditsClientLoaders() {}

    public static void registerReloadListeners(DtReloadListenerRegistrar registrar) {
        registrar.register(new ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(ResourceManager resourceManager) {
                LocalizationCreditRegistry.load(resourceManager);
            }

            @Override
            public String getName() {
                return "dungeontrain:localization_credits";
            }
        });
    }
}
