package games.brennan.dungeontrain.client.constellation;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.block.ConstellationBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

/**
 * Drives the constellation patch: once a frame, is the player in a block, how far open is the patch,
 * where is the crosshair on it, and redraw its picture; then draw it over the finished level.
 *
 * <p>Drawn at {@code AFTER_WEATHER}, the last level stage, so it covers terrain, carriages, entities,
 * particles and weather, but the hand and HUD still draw over it. Everything else on screen stays the
 * world: only the disc is the night sky.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ConstellationClient {

    private static final ConstellationFade FADE = new ConstellationFade();
    private static float alpha;
    /** For the window block's punch: while the player stands in one, its own cubes are not punched. */
    private static boolean inside;

    private ConstellationClient() {}

    /** Is the player standing in a constellation block right now? */
    public static boolean inside() {
        return inside;
    }

    @SubscribeEvent
    public static void onFrameStart(RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (mc.level == null || player == null) {
            inside = false;
            alpha = 0f;
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        inside = !player.isSpectator() && ConstellationInside.test(mc.level, player, partial);
        alpha = FADE.advance(inside, System.nanoTime());
        // The picture is wanted by an open patch, and by a window block in view (it asks for one).
        if (!FADE.visible() && !ConstellationSky.wanted()) return;
        if (!ConstellationBlocks.active()) {
            ConstellationSky.release();
            return;
        }
        Vector3f look = FADE.visible() ? mc.gameRenderer.getMainCamera().getLookVector() : null;
        ConstellationSky.aim(look);
        ConstellationSky.refresh(partial);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) return;
        if (alpha <= ConstellationFade.OFF_EPSILON) return;
        ConstellationSky.drawPatch(event.getModelViewMatrix(), alpha, ConstellationEdge.current());
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ConstellationSky.tick();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        FADE.reset();
        alpha = 0f;
        inside = false;
        ConstellationSky.release();
    }
}
