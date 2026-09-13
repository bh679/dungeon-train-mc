package games.brennan.dungeontrain.client;

import games.brennan.discordpresence.client.SurveySubmitClientHook;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import games.brennan.dungeontrain.client.skybox.SkyboxStencil;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-side bootstrap. Registers the mod-list "settings" screen via
 * {@link IConfigScreenFactory}.
 *
 * <p>Kept separate from {@link DungeonTrain} so the dedicated server never
 * touches client-only types like {@code IConfigScreenFactory}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DungeonTrainClient {

    private DungeonTrainClient() {}

    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        ModList.get().getModContainerById(DungeonTrain.MOD_ID).ifPresent(container ->
            container.registerExtensionPoint(
                IConfigScreenFactory.class,
                (mc, parent) -> new DungeonTrainSettingsScreen(parent)));

        // Route bug-report answers submitted from DP's on-demand survey (/bug, /feedback) into
        // the same log-collection path the death screen uses.
        SurveySubmitClientHook.register(BugLogReporter::maybeReport);

        // "@s" in the chat box for a non-op capstone-holder: the server decides for real, this only
        // keeps the syntax highlighter from painting the selector red once the server has exposed
        // /advancement to us.
        games.brennan.dungeontrain.advancement.SelfSelectorGrant.setClientCheck(
            SelfSelectorClientCheck::serverExposedAdvancementCommand);

        // Distant Horizons draws its own LODs of the real world: it never sees the upside-down band's
        // block flip, and it never sees that a dimensional carriage is meant to be somewhere other
        // than the coordinates it is stamped at. Bind the per-frame suppression for both — behind the
        // ModList check, because DistantHorizonsSuppression is the one DT class that names DH types
        // and must not be loaded when DH isn't installed.
        if (GraphicsCapabilities.distantHorizonsActive()) {
            DistantHorizonsSuppression.register();
        }

        // Skybox blocks mask each variant's sky with the stencil buffer, which Minecraft's
        // main render target does not allocate by default. enqueueWork because this setup
        // event runs on a parallel mod-loading thread while enableStencil() re-creates the
        // framebuffer's attachments — GL work that must happen on the render thread.
        event.enqueueWork(SkyboxStencil::requestStencil);
    }
}
