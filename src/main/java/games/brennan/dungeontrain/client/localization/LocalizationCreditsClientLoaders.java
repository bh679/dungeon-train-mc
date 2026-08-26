package games.brennan.dungeontrain.client.localization;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.localization.edit.LocalizationCoverage;
import games.brennan.dungeontrain.client.localization.edit.ProvenanceManifestRegistry;
import games.brennan.dungeontrain.client.localization.edit.TranslationVariableExamples;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/**
 * NeoForge registration seam for the localization-metadata registries:
 * {@link LocalizationCreditRegistry}, {@link TranslationContributorsRegistry},
 * {@link ProvenanceManifestRegistry}, {@link LanguageSearchIndex} and
 * {@link TranslationVariableExamples}. Registers their {@code load} methods as
 * <b>client</b>-resource-manager reload listeners on
 * {@code RegisterClientReloadListenersEvent}, which fires once at {@code Minecraft}
 * construction and again on every resource-pack reload — so credits are populated
 * well before the title screen first renders and update live if the player toggles
 * a localization pack from the title screen's own Options menu.
 *
 * <p>Deliberately the client resource channel, not the server datapack
 * channel {@code NarrativeDataLoaders} uses — the main menu has no
 * world/server running yet, so only resource packs are active there.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LocalizationCreditsClientLoaders {

    private LocalizationCreditsClientLoaders() {}

    @SubscribeEvent
    public static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(ResourceManager resourceManager) {
                LocalizationCreditRegistry.load(resourceManager);
                // The lang files it falls back to counting may have changed with the pack stack.
                LocalizationCoverage.invalidate();
            }

            @Override
            public String getName() {
                return "dungeontrain:localization_credits";
            }
        });
        event.registerReloadListener(new ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(ResourceManager resourceManager) {
                LanguageSearchIndex.load(resourceManager);
            }

            @Override
            public String getName() {
                return "dungeontrain:language_search_countries";
            }
        });
        event.registerReloadListener(new ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(ResourceManager resourceManager) {
                TranslationContributorsRegistry.load(resourceManager);
            }

            @Override
            public String getName() {
                return "dungeontrain:translation_contributors";
            }
        });
        event.registerReloadListener(new ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(ResourceManager resourceManager) {
                ProvenanceManifestRegistry.load(resourceManager);
            }

            @Override
            public String getName() {
                return "dungeontrain:localization_provenance";
            }
        });
        event.registerReloadListener(new ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(ResourceManager resourceManager) {
                TranslationVariableExamples.load(resourceManager);
            }

            @Override
            public String getName() {
                return "dungeontrain:translation_examples";
            }
        });
    }
}
