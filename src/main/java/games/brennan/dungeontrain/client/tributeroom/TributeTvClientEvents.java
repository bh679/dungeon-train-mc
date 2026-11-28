package games.brennan.dungeontrain.client.tributeroom;

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
 * The client events a tribute room visit rides on: the frame it draws its TV picture in, the tick, a
 * right-click on the TV, the loading screen a cross-dimension respawn would put up over the fade,
 * and logging out.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class TributeTvClientEvents {

    private static final org.slf4j.Logger PERF = org.slf4j.LoggerFactory.getLogger(games.brennan.dungeontrain.util.DtLogging.PERF);
    /** The {@code [tributetv]} line's cadence, the {@code [frame]} line's. */
    private static final long PERF_WINDOW_MS = 10_000L;
    private static long perfWindowStartMs;
    /** Client ticks, for telling a held left-click from a new one. */
    private static long clientTicks;
    /** The tick of the last left-click event on a showing TV. */
    private static long lastTvLeftClickTick = Long.MIN_VALUE / 2;

    private TributeTvClientEvents() {}

    @SubscribeEvent
    public static void onFrameStart(RenderFrameEvent.Pre event) {
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        TributeTvVisit.onFrameStart(partial);
        TributeTvShowcase.onFrameStart(partial);
    }

    /** The tube walk's darkness over the HUD, while no screen is up to draw it. */
    @SubscribeEvent
    public static void onGuiRendered(net.neoforged.neoforge.client.event.RenderGuiEvent.Post event) {
        if (Minecraft.getInstance().screen instanceof TributeArrivalScreen) return;
        float black = TributeTvVisit.tubeBlack();
        if (black <= 0f) return;
        net.minecraft.client.gui.GuiGraphics g = event.getGuiGraphics();
        int alpha = net.minecraft.util.Mth.clamp(Math.round(black * 255f), 0, 255);
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), alpha << 24);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        clientTicks++;
        TributeTvVisit.onClientTick();
        TributeTvShowcase.onClientTick();
        logPerf();
    }

    /**
     * Every 10 s while any TV picture is up: how many screens were drawn into TVs, and how many targets
     * are alive. Zero draws with TVs in range means none of them was on screen.
     */
    private static void logPerf() {
        int entries = TributeTvShowcase.entryCount() + (TributeTvVisit.texture() != null ? 1 : 0);
        long now = net.minecraft.Util.getMillis();
        if (entries == 0) {
            perfWindowStartMs = 0;
            ScreenToTexture.drainCounters();
            return;
        }
        if (perfWindowStartMs == 0) {
            perfWindowStartMs = now;
            ScreenToTexture.drainCounters();
            return;
        }
        long wall = now - perfWindowStartMs;
        if (wall < PERF_WINDOW_MS) return;
        perfWindowStartMs = now;
        int[] c = ScreenToTexture.drainCounters();
        games.brennan.dungeontrain.util.AsyncPerfLog.debug(PERF, String.format(java.util.Locale.ROOT,
            "[tributetv] tvs=%d draws=%d drawsPerSec=%.1f targets=%d always=%s",
            entries, c[0], c[0] * 1000.0 / wall, c[1], ScreenToTexture.alwaysDrawForDev));
    }

    /** The TV is the way on: a right-click on it is ours, and never reaches the server's channel cycle. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()) return;
        if (pressedTvButton(event)) return;
        claimTvClick(event);
    }

    /**
     * Takes a click on a showing TV: the event is cancelled now, and the page gets the click on the next
     * tick. A page's button can leave the world (Leave, Quit, Board anew), and vanilla goes on reading the
     * level and the player after this event, cancelled or not: leaving from inside it crashed on a null
     * level. {@code tell} always queues, where {@code execute} would run it here on the render thread.
     */
    private static void claimTvClick(PlayerInteractEvent event) {
        net.minecraft.core.BlockPos pos = event.getPos().immutable();
        if (!TributeTvVisit.ownsClick(pos) && !TributeTvShowcase.ownsClick(pos)) return;
        boolean sneak = event.getEntity().isShiftKeyDown();
        if (event instanceof net.neoforged.bus.api.ICancellableEvent c) c.setCanceled(true);
        PlayerInteractEvent.LeftClickBlock.Action action = null;
        long ticksSinceLeft = Long.MAX_VALUE;
        if (event instanceof PlayerInteractEvent.LeftClickBlock left) {
            action = left.getAction();
            ticksSinceLeft = clientTicks - lastTvLeftClickTick;
            lastTvLeftClickTick = clientTicks;
        }
        if (!deliversClick(event instanceof PlayerInteractEvent.RightClickBlock, event.getHand(), action, ticksSinceLeft)) return;
        Minecraft.getInstance().tell(() -> {
            if (!TributeTvVisit.onTvClicked(pos, sneak)) TributeTvShowcase.onTvClicked(pos, sneak);
        });
    }

    /**
     * Whether a (cancelled) interaction event on a showing TV is a fresh press the page should get. One
     * press fires several: a right-click cancelled with the default PASS goes on to the off hand, and a
     * cancelled left-click starts over (another {@code START}) every tick the button stays down. Each one
     * queued a page click, so the photo viewer's Next stepped two or more photos at a time. A left-click
     * is new only when none came the tick before ({@code ticksSinceLastLeft} &gt; 1).
     */
    static boolean deliversClick(boolean rightClick, net.minecraft.world.InteractionHand hand,
                                 PlayerInteractEvent.LeftClickBlock.Action leftAction, long ticksSinceLastLeft) {
        if (rightClick) return hand == net.minecraft.world.InteractionHand.MAIN_HAND;
        return leftAction == PlayerInteractEvent.LeftClickBlock.Action.START && ticksSinceLastLeft > 1;
    }

    /**
     * The button under a tribute TV ({@code TributeTvButton}): Next Screen on that TV's picture. Not
     * cancelled, so the server still presses the button (its click and its pulse).
     */
    private static boolean pressedTvButton(PlayerInteractEvent.RightClickBlock event) {
        net.minecraft.world.level.block.state.BlockState state = event.getLevel().getBlockState(event.getPos());
        // One hand's pass: a button takes the main hand's use, so the off hand never asks again.
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return false;
        if (!games.brennan.dungeontrain.portal.tribute.TributeTvButton.isButton(state)) return false;
        if (state.getValue(net.minecraft.world.level.block.ButtonBlock.FACE) != net.minecraft.world.level.block.state.properties.AttachFace.WALL) return false;
        net.minecraft.core.BlockPos tv = games.brennan.dungeontrain.portal.tribute.TributeTvButton.tvAbove(
            event.getPos(), state.getValue(net.minecraft.world.level.block.ButtonBlock.FACING));
        return TributeTvVisit.pressNext(tv) || TributeTvShowcase.pressNext(tv);
    }

    /**
     * A respawn into another dimension opens vanilla's loading screen, which would replace the fade.
     * Keep the fade: the visit waits for the TV itself rather than for that screen.
     */
    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!TributeTvVisit.isActive()) return;
        if (!(event.getNewScreen() instanceof ReceivingLevelScreen) && !(event.getNewScreen() instanceof LevelLoadingScreen)) return;
        if (event.getCurrentScreen() instanceof TributeArrivalScreen) event.setCanceled(true);
    }

    /** A left click on the TV is a click on the page too, never a swing at the block. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getLevel().isClientSide()) return;
        claimTvClick(event);
    }

    @SubscribeEvent
    public static void onMouseScroll(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent event) {
        if (TributeTvVisit.onTvScrolled(event.getScrollDeltaX(), event.getScrollDeltaY())
            || TributeTvShowcase.onTvScrolled(event.getScrollDeltaX(), event.getScrollDeltaY())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        net.minecraft.client.Minecraft.getInstance().execute(() -> {
            TributeTvVisit.reset();
            TributeTvShowcase.reset();
        });
    }
}
