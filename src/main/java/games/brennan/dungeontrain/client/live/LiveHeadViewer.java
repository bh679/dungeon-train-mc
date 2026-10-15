package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.TvItems;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Random;

/**
 * The live feed pinned to the top-right of the screen while the player wears the camcorder or a
 * Vista TV on their head ({@link LiveHeadViewerHotkey} turns it off and on).
 *
 * <p>It shows whatever the channel shows — the same shared decode every TV in the world draws
 * ({@link LiveFeedSource#frame}). While this client is the one broadcasting, its own picture only
 * reaches the channel a segment or two later, so until it does the box is blacked out with a
 * "Live in" countdown over whatever the channel is still playing.</p>
 *
 * <p>Where it is drawn depends on who is wearing it. A viewer's box is a HUD layer, so the
 * inventory, the pause menu and advancement toasts all sit on top of it. The streamer's box is
 * drawn at {@link EventPriority#LOWEST} on {@link RenderFrameEvent.Post}, after
 * {@link LiveStreamController} has grabbed the frame, so it stays on top for them but never reaches
 * the broadcast (no picture-in-picture echo).</p>
 *
 * <p>Clicking the box in the inventory or chat cycles its size ({@link LiveFeedClientConfig#cycleHeadViewerSize});
 * the streamer and the viewer each keep their own size, the streamer's smaller by default.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveHeadViewer {

    /** Gap from the screen edge; the viewfinder's badge is top-left, so the top-right is free. */
    static final int EDGE = 8;
    static final int BORDER = 1;
    /** Viewers run one to two segments behind; this is the far end, plus upload slack. */
    static final int SEGMENTS_BEHIND = 2;
    static final long UPLOAD_SLACK_MS = 2_000;
    static final float NUMBER_SCALE = 3f;
    static final int NOISE_CELL = 3;
    static final int DOT = 3;
    static final int DOT_GAP = 2;
    static final long DOT_STEP_MS = 350;

    private static final Component COUNTDOWN = Component.translatable("gui.dungeontrain.live.countdown");
    private static final int BORDER_COLOR = 0xFF101010;
    private static final int DARK = 0xFF181818;
    private static final int BLACKOUT = 0xC8000000;
    private static final Random NOISE = new Random();
    /** Side of the pre-rolled speckle texture, in cells; wider than the largest box (720 / {@link #NOISE_CELL}). */
    static final int NOISE_SIZE = 256;
    private static final ResourceLocation NOISE_TEXTURE = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live/noise");
    private static boolean noiseReady; // render thread

    private LiveHeadViewer() {}

    /** Where the box goes, in GUI pixels (border excluded). */
    record Box(int x, int y, int w, int h) {}

    static Box layout(int guiWidth, int boxWidth) {
        return new Box(guiWidth - EDGE - boxWidth, EDGE, boxWidth, LiveFeedClientConfig.heightFor(boxWidth));
    }

    /** Whole seconds until this client's broadcast should be on the channel; never below 1. */
    static int countdownSeconds(long startedMs, int segmentSeconds, long nowMs) {
        long due = startedMs + SEGMENTS_BEHIND * segmentSeconds * 1000L + UPLOAD_SLACK_MS;
        long left = due - nowMs;
        return (int) Math.max(1, (left + 999) / 1000);
    }

    /** True for the camcorder and for any Vista TV worn as a hat. */
    static boolean isViewerHat(ItemStack head) {
        if (head.isEmpty()) return false;
        return head.is(ModItems.LIVE_HEADPIECE.get()) || TvItems.isTv(head);
    }

    /** Wearing a viewer hat with the box switched on — the box is shown in one of the two passes. */
    private static boolean shown(Minecraft mc) {
        return mc.level != null && mc.player != null && LiveFeedClientConfig.headViewerEnabled()
            && isViewerHat(mc.player.getItemBySlot(EquipmentSlot.HEAD));
    }

    /** Watching: a HUD layer, under every screen and toast. */
    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "live_head_viewer"), (g, delta) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null || LiveStreamController.get().streaming() || mc.options.hideGui) return;
            if (shown(mc)) {
                draw(g, LiveFeedSource.MAIN.frame(mc.player.tickCount, delta.getGameTimeDeltaPartialTick(false), false));
            } else {
                // Box switched off: a running takeover vote's countdown still shows where it would be.
                voteBanner(g, layout(g.guiWidth(), LiveFeedClientConfig.headViewerWidth(false)), mc.font);
            }
        });
    }

    /** Streaming: after the frame grab, on top of everything, never in the broadcast. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFrameEnd(RenderFrameEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!shown(mc) || !LiveStreamController.get().streaming()) return;
        if (mc.screen == null && mc.options.hideGui) return;
        LiveFeedSource.Frame frame = LiveFeedSource.MAIN.frame(mc.player.tickCount,
            event.getPartialTick().getGameTimeDeltaPartialTick(false), false);
        LiveRecOverlay.guiPass(g -> {
            draw(g, frame);
            LiveStreamSettingsButton.draw(g, layout(g.guiWidth(), LiveFeedClientConfig.headViewerWidth(true)));
        });
    }

    /**
     * In the inventory or chat, a left click on the box cycles its size and goes no further. While
     * streaming, a click on the settings button under it ({@link LiveStreamSettingsButton}) opens the
     * Stream options page instead.
     */
    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT || !isResizeScreen(event.getScreen())) return;
        Minecraft mc = Minecraft.getInstance();
        if (!shown(mc)) return;
        boolean streaming = LiveStreamController.get().streaming();
        Box b = layout(mc.getWindow().getGuiScaledWidth(), LiveFeedClientConfig.headViewerWidth(streaming));
        if (streaming && LiveStreamSettingsButton.click(event.getScreen(), b, event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
            return;
        }
        if (!contains(b, event.getMouseX(), event.getMouseY())) return;
        event.setCanceled(true);
        LiveFeedClientConfig.cycleHeadViewerSize(streaming);
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    /** The screens the box can be resized from: the inventory (survival or creative) and chat. */
    static boolean isResizeScreen(@Nullable Screen screen) {
        return screen != null && resizesFrom(screen.getClass());
    }

    static boolean resizesFrom(Class<?> screen) {
        return InventoryScreen.class.isAssignableFrom(screen)
            || CreativeModeInventoryScreen.class.isAssignableFrom(screen)
            || ChatScreen.class.isAssignableFrom(screen);
    }

    /** Inside the box or on its border. */
    static boolean contains(Box b, double x, double y) {
        return x >= b.x() - BORDER && x < b.x() + b.w() + BORDER && y >= b.y() - BORDER && y < b.y() + b.h() + BORDER;
    }

    private static void draw(GuiGraphics g, LiveFeedSource.Frame frame) {
        LiveStreamController streamer = LiveStreamController.get();
        boolean streaming = streamer.streaming();
        Font font = Minecraft.getInstance().font;
        Box b = layout(g.guiWidth(), LiveFeedClientConfig.headViewerWidth(streaming));

        g.fill(b.x() - BORDER, b.y() - BORDER, b.x() + b.w() + BORDER, b.y() + b.h() + BORDER, BORDER_COLOR);
        switch (frame.kind()) {
            case PICTURE -> g.blit(LiveFeedSource.texture(), b.x(), b.y(), b.w(), b.h(), 0f, 0f, 1, 1, 1, 1);
            case STATIC -> noise(g, b);
            case DOWNLOADING, WAITING -> g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), DARK);
        }
        if (frame.kind() == LiveFeedSource.Kind.PICTURE && frame.buffering()) {
            bufferingMark(g, b, bufferingDot(System.currentTimeMillis()));
        }

        if (streaming && !showingOwnBroadcast(frame, streamer.playlistUrl())) {
            int n = countdownSeconds(streamer.streamStartedMs(), LiveFeedClientConfig.segmentSeconds(),
                System.currentTimeMillis());
            countdown(g, b, font, n);
        }
        voteBanner(g, b, font);
    }

    /**
     * The wearer's takeover vote is running ({@link LiveTakeoverFlow}): "The viewers are voting… 14 s" on a
     * dark strip along the top of the box, over the stream they are asking to take.
     */
    private static void voteBanner(GuiGraphics g, Box b, Font font) {
        int seconds = LiveTakeoverFlow.voteCountdownSeconds();
        if (seconds < 0) return;
        Component text = Component.translatable("screen.dungeontrain.live_takeover.voting", seconds);
        int pad = 3;
        // Shrunk to fit a narrow box (the streamer's own is smaller), so the seconds are never cut off.
        float scale = Math.min(1f, (b.w() - 2f * pad) / Math.max(1, font.width(text)));
        int h = Math.round(font.lineHeight * scale) + 2 * pad;
        g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + h, BLACKOUT);
        float w = font.width(text) * scale;
        g.pose().pushPose();
        g.pose().translate(b.x() + (b.w() - w) / 2f, b.y() + pad + 0.5f, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, text, 0, 0, 0xFFFFFFFF, true);
        g.pose().popPose();
    }

    /** The box is playing this client's own broadcast (live, not the dark-channel replay). */
    static boolean showingOwnBroadcast(LiveFeedSource.Frame frame, @Nullable String ownPlaylist) {
        return frame.kind() == LiveFeedSource.Kind.PICTURE && !frame.replay()
            && ownPlaylist != null && Objects.equals(frame.url(), ownPlaylist);
    }

    /** Black the picture out and centre "Live in" over a bigger number. */
    private static void countdown(GuiGraphics g, Box b, Font font, int seconds) {
        g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), BLACKOUT);
        String num = Integer.toString(seconds);
        int labelW = font.width(COUNTDOWN);
        int numW = Math.round(font.width(num) * NUMBER_SCALE);
        int numH = Math.round(font.lineHeight * NUMBER_SCALE);
        int gap = 3;
        int blockH = font.lineHeight + gap + numH;
        int top = b.y() + (b.h() - blockH) / 2;
        int cx = b.x() + b.w() / 2;
        g.drawString(font, COUNTDOWN, cx - labelW / 2, top, 0xFFFFFFFF, true);
        g.pose().pushPose();
        g.pose().translate(cx - numW / 2f, top + font.lineHeight + gap, 0);
        g.pose().scale(NUMBER_SCALE, NUMBER_SCALE, 1f);
        g.drawString(font, num, 0, 0, 0xFFFFFFFF, true);
        g.pose().popPose();
    }

    /** Which of the three buffering dots is lit at {@code nowMs}: one step every {@link #DOT_STEP_MS}. */
    static int bufferingDot(long nowMs) {
        return (int) Math.floorMod(nowMs / DOT_STEP_MS, 3L);
    }

    /**
     * The picture is stalled on the next segment: three dots on a dark backing in the bottom-right
     * corner, the lit one stepping along — the TV screens' loading overlay, drawn for the HUD.
     */
    private static void bufferingMark(GuiGraphics g, Box b, int lit) {
        int pad = 2;
        int w = 3 * DOT + 2 * DOT_GAP + 2 * pad;
        int h = DOT + 2 * pad;
        int x = b.x() + b.w() - w - 3;
        int y = b.y() + b.h() - h - 3;
        g.fill(x, y, x + w, y + h, BLACKOUT);
        for (int i = 0; i < 3; i++) {
            int dx = x + pad + i * (DOT + DOT_GAP);
            g.fill(dx, y + pad, dx + DOT, y + pad + DOT, i == lit ? 0xFFFFFFFF : 0xFF707070);
        }
    }

    /**
     * Snow for a dark channel: a window of a pre-rolled grey speckle texture, one texel per
     * {@link #NOISE_CELL} GUI pixels, from a fresh random offset each frame — one blit, where filling
     * every cell cost thousands of fills a frame.
     */
    private static void noise(GuiGraphics g, Box b) {
        ensureNoiseTexture();
        int cellsW = Math.min(NOISE_SIZE, (b.w() + NOISE_CELL - 1) / NOISE_CELL);
        int cellsH = Math.min(NOISE_SIZE, (b.h() + NOISE_CELL - 1) / NOISE_CELL);
        int u = NOISE.nextInt(NOISE_SIZE - cellsW + 1);
        int v = NOISE.nextInt(NOISE_SIZE - cellsH + 1);
        g.blit(NOISE_TEXTURE, b.x(), b.y(), b.w(), b.h(), u, v, cellsW, cellsH, NOISE_SIZE, NOISE_SIZE);
    }

    /** Render thread: rolls the speckle once and registers it (nearest-filtered, so cells stay crisp). */
    private static void ensureNoiseTexture() {
        if (noiseReady) return;
        NativeImage image = new NativeImage(NOISE_SIZE, NOISE_SIZE, false);
        for (int y = 0; y < NOISE_SIZE; y++) {
            for (int x = 0; x < NOISE_SIZE; x++) {
                int v = 40 + NOISE.nextInt(160);
                image.setPixelRGBA(x, y, 0xFF000000 | v << 16 | v << 8 | v); // grey, so ABGR order doesn't matter
            }
        }
        Minecraft.getInstance().getTextureManager().register(NOISE_TEXTURE, new DynamicTexture(image));
        noiseReady = true;
    }
}
