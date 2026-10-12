package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import io.github.mortuusars.exposure.client.camera.CameraClient;
import io.github.mortuusars.exposure.client.camera.viewfinder.Viewfinder;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Left-click takes a photo too while looking through a camera's viewfinder.
 *
 * <p>Exposure swallows the attack button in the viewfinder (its {@code MouseHandlerMixin} →
 * {@code Viewfinder#mouseClicked}), so only use (right-click) fires the shutter. NeoForge's
 * {@link InputEvent.MouseButton.Pre} runs ahead of that inject, so here the attack press is turned into
 * one use-key click — the same {@code startUseItem} path a right-click takes — and cancelled.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ViewfinderAttackShutter {

    private ViewfinderAttackShutter() {}

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getAction() != GLFW.GLFW_PRESS || mc.screen != null || mc.player == null
                || !mc.options.keyAttack.matchesMouse(event.getButton())) {
            return;
        }
        Viewfinder viewfinder = CameraClient.viewfinder();
        if (viewfinder == null || !viewfinder.isLookingThrough() || viewfinder.controlsActive()) {
            return;
        }
        KeyMapping.click(mc.options.keyUse.getKey());
        event.setCanceled(true);
    }
}
