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

        // Distant Horizons draws its own LODs and never sees the upside-down band's block flip, so
        // in-band its horizon stands upright under inverted terrain. Bind the per-frame suppression
        // — behind the ModList check, because DistantHorizonsUpsideDown is the one DT class that
        // names DH types and must not be loaded when DH isn't installed.
        if (GraphicsCapabilities.distantHorizonsActive()) {
            DistantHorizonsUpsideDown.register();
        }

        // Skybox blocks mask each variant's sky with the stencil buffer, which Minecraft's
        // main render target does not allocate by default. enqueueWork because this setup
        // event runs on a parallel mod-loading thread while enableStencil() re-creates the
        // framebuffer's attachments — GL work that must happen on the render thread.
        event.enqueueWork(SkyboxStencil::requestStencil);
    }
}
