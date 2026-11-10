package games.brennan.dungeontrain.client.logo;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.slf4j.Logger;

import java.io.IOException;

/** Loads the ring logo's shader, and frees its (and the menu backdrop's) GPU memory once no screen is drawing them. */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LogoRingClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation SHADER_ID = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "logo_ring");

    private LogoRingClient() {}

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), SHADER_ID, DefaultVertexFormat.BLOCK),
                    loaded -> LogoRingRenderer.get().setShader(loaded));
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] logo_ring shader failed to load; the logo ring is off", e);
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        LogoRingRenderer.get().releaseIfIdle();
        MenuBackdrop.get().releaseIfIdle();
    }
}
