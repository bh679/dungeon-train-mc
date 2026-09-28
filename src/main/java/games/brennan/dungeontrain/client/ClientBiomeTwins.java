package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.worldgen.VanillaBiomeTwins;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Client side of {@link VanillaBiomeTwins}: builds the twins from the client's own biome registry
 * at login (a dedicated-server client has its own biome objects), clears them at logout, and hands
 * the camera's world X to the position-less sky/fog/sound questions (foliage and water tint go by
 * the block's X instead — see {@code BiomeColorsTwinMixin}). Off the overworld the camera
 * reports a far-negative X, which is "outside the WWOO stretch" — the twin (vanilla) answers, which
 * is what those biomes look like there anyway.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ClientBiomeTwins {

    private static final double OFF_OVERWORLD_X = -1.0e9;

    private ClientBiomeTwins() {}

    @EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    static final class Setup {
        private Setup() {}

        @SubscribeEvent
        static void onClientSetup(FMLClientSetupEvent event) {
            VanillaBiomeTwins.setCameraX(ClientBiomeTwins::cameraX);
        }
    }

    private static double cameraX() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != Level.OVERWORLD) return OFF_OVERWORLD_X;
        return mc.gameRenderer.getMainCamera().getPosition().x;
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        VanillaBiomeTwins.build(event.getPlayer().registryAccess(), "client");
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        VanillaBiomeTwins.clear();
        VanillaBiomeTwins.setClientWorldHasTrain(false);
    }
}
