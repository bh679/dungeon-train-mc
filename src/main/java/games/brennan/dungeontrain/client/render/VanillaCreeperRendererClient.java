package games.brennan.dungeontrain.client.render;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.renderer.entity.CreeperRenderer;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Keeps the vanilla creeper looking vanilla. Creeper Overhaul's client config ({@code replaceDefaultCreeper}, on by
 * default) swaps its renderer for the mod's model during the parallel client setup; DT puts vanilla's back in
 * {@code enqueueWork}, which runs once every mod's parallel setup has finished. The mod's own creepers keep theirs.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class VanillaCreeperRendererClient {

    private VanillaCreeperRendererClient() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> EntityRenderers.register(EntityType.CREEPER, CreeperRenderer::new));
    }
}
