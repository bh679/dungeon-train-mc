package games.brennan.dungeontrain.client.homeroom;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The client events a home room visit rides on: the frame it draws its TV picture in, the tick, a
 * right-click on the TV, the loading screen a cross-dimension respawn would put up over the fade,
 * and logging out.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class HomeTvClientEvents {

    private HomeTvClientEvents() {}

    @SubscribeEvent
    public static void onFrameStart(RenderFrameEvent.Pre event) {
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        HomeTvVisit.onFrameStart(partial);
        HomeTvShowcase.onFrameStart(partial);
    }

    /** The tube walk's darkness over the HUD, while no screen is up to draw it. */
    @SubscribeEvent
    public static void onGuiRendered(net.neoforged.neoforge.client.event.RenderGuiEvent.Post event) {
        if (Minecraft.getInstance().screen instanceof HomeArrivalScreen) return;
        float black = HomeTvVisit.tubeBlack();
        if (black <= 0f) return;
        net.minecraft.client.gui.GuiGraphics g = event.getGuiGraphics();
        int alpha = net.minecraft.util.Mth.clamp(Math.round(black * 255f), 0, 255);
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), alpha << 24);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        HomeTvVisit.onClientTick();
        HomeTvShowcase.onClientTick();
    }

    /** The TV is the way on: a right-click on it is ours, and never reaches the server's channel cycle. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()) return;
        boolean sneak = event.getEntity().isShiftKeyDown();
        if (HomeTvVisit.onTvClicked(event.getPos(), sneak) || HomeTvShowcase.onTvClicked(event.getPos(), sneak)) event.setCanceled(true);
    }

    /**
     * A respawn into another dimension opens vanilla's loading screen, which would replace the fade.
     * Keep the fade: the visit waits for the TV itself rather than for that screen.
     */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!HomeTvVisit.isActive()) return;
        if (!(event.getNewScreen() instanceof ReceivingLevelScreen) && !(event.getNewScreen() instanceof LevelLoadingScreen)) return;
        if (event.getCurrentScreen() instanceof HomeArrivalScreen) event.setCanceled(true);
    }

    /** A left click on the TV is a click on the page too, never a swing at the block. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getLevel().isClientSide()) return;
        boolean sneak = event.getEntity().isShiftKeyDown();
        if (HomeTvVisit.onTvClicked(event.getPos(), sneak) || HomeTvShowcase.onTvClicked(event.getPos(), sneak)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMouseScroll(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent event) {
        if (HomeTvVisit.onTvScrolled(event.getScrollDeltaX(), event.getScrollDeltaY())
            || HomeTvShowcase.onTvScrolled(event.getScrollDeltaX(), event.getScrollDeltaY())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        net.minecraft.client.Minecraft.getInstance().execute(() -> {
            HomeTvVisit.reset();
            HomeTvShowcase.reset();
        });
    }
}
