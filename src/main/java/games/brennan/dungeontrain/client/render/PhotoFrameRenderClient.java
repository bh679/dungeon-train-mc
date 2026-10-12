package games.brennan.dungeontrain.client.render;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.registry.ModBlockEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Registers the block photo frame's renderer. */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class PhotoFrameRenderClient {

    private PhotoFrameRenderClient() {}

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.PHOTOGRAPH_FRAME.get(), PhotographFrameBlockRenderer::new);
    }
}
