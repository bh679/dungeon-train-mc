package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.photo.WornPhotographs;
import io.github.mortuusars.exposure.ExposureClient;
import io.github.mortuusars.exposure.client.render.photograph.PhotographStyle;
import io.github.mortuusars.exposure.client.render.photograph.PhotographStyles;
import io.github.mortuusars.exposure.world.photograph.PhotographType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Tells Exposure which paper texture each of DT's worn photograph types is drawn on. */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class WornPhotographStyles {

    // Exposure's own "no overlay" marker. It compares against this, so null here crashes the renderer.
    private static final ResourceLocation NO_OVERLAY = ExposureClient.Textures.EMPTY;
    private static final ResourceLocation ALBUM_PAPER = ExposureClient.Textures.Photograph.REGULAR_ALBUM_PAPER;

    private WornPhotographStyles() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        register(WornPhotographs.LIGHT, "worn_1");
        register(WornPhotographs.MEDIUM, "worn_2");
        register(WornPhotographs.HEAVY, "worn_3");
    }

    private static void register(PhotographType type, String texture) {
        ResourceLocation paper = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "textures/photograph/" + texture + ".png");
        PhotographStyles.register(type, new PhotographStyle(paper, NO_OVERLAY, ALBUM_PAPER, NO_OVERLAY,
            new TornEdgeEffect("dt_torn_" + texture, paper)));
    }
}
