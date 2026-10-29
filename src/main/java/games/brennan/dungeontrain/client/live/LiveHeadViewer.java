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
 *
 * <p>While two streams are live (the Two Streams reward) a viewer's box becomes two panes, the
 * second stream under the first ({@link HeadViewerPanes}), unless the box is at its largest size;
 * in the inventory a click on a pane makes that stream the only one and a click on the lone pane
 * brings both back. The streamer's own preview does the same with their stream and the other one
 * ({@link StreamerBoxPanes}): their own by default, and the setting can also pick the other, both or none.</p>
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
    private static final Component DOWNLOADING = Component.translatable("gui.dungeontrain.live.downloading");
    private static final int LABEL_PAD = 3;
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

    /**
     * The box for a stream {@code ratio} (width over height) wide: as wide as the size setting says, as
     * tall as the stream's own shape needs, so the whole picture shows, never cropped or stretched.
     */
    static Box layout(int guiWidth, int boxWidth, float ratio) {
        float r = LiveAspect.clampRatio(ratio);
        if (Math.abs(r - 16f / 9f) < 1e-3f) return layout(guiWidth, boxWidth);
        return new Box(guiWidth - EDGE - boxWidth, EDGE, boxWidth, Math.max(1, Math.round(boxWidth / r)));
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
                float partial = delta.getGameTimeDeltaPartialTick(false);
                int width = LiveFeedClientConfig.headViewerWidth(false);
                ItemStack head = mc.player.getItemBySlot(EquipmentSlot.HEAD);
                HeadViewerPanes.wearing(TvItems.isTv(head), TvItems.stream(head), LiveFeedClientConfig.wornTvStreams(),
                    System.currentTimeMillis());
                var panes = panes(g.guiWidth(), g.guiHeight(), width, secondLive(), LiveFeedClientConfig.headViewerAtMaxSize(false));
                for (HeadViewerPanes.Pane pane : panes) {
                    LiveFeedSource source = pane.second() ? LiveFeedSource.SECOND : LiveFeedSource.MAIN;
                    draw(g, source, source.frame(mc.player.tickCount, partial, false), pane.box(), !pane.second(),
                        mc.player.tickCount, partial);
                }
                // The takeover button hangs under the lowest pane, so a second pane never covers it.
                LiveTakeoverButton.draw(g, panes.get(panes.size() - 1).box());
            } else {
                // Box switched off: a running takeover vote's countdown still shows where it would be.
                voteBanner(g, layout(g.guiWidth(), LiveFeedClientConfig.headViewerWidth(false), LiveFeedSource.MAIN.ratio()), mc.font);
            }
        });
    }

    /** Streaming: after the frame grab, on top of everything, never in the broadcast. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onFrameEnd(RenderFrameEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!shown(mc) || !LiveStreamController.get().streaming()) return;
        if (mc.screen == null && mc.options.hideGui) return;
        // The streamer's own picture (the second stream's while they hold the relay's second channel),
        // the other stream's, both or none (StreamerBoxPanes).
        LiveFeedSource own = ownSource();
        LiveFeedSource other = otherThan(own);
        var panes = streamerPanes(mc);
        if (panes.isEmpty()) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        LiveFeedSource.Frame[] frames = new LiveFeedSource.Frame[panes.size()];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = (panes.get(i).second() ? other : own).frame(mc.player.tickCount, partial, false);
        }
        // In the inventory the box sits behind the inventory's parts unless the cursor is on it (StreamerBoxOcclusion).
        var behind = behindInventory(mc, panes);
        LiveRecOverlay.guiPass(g -> {
            // The screen's depth is still in the buffer: without clearing it, its higher layers (the effect
            // list's text) would show through the box even when the box is meant to be on top.
            com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            for (int i = 0; i < frames.length; i++) {
                HeadViewerPanes.Pane pane = panes.get(i);
                LiveFeedSource source = pane.second() ? other : own;
                LiveFeedSource.Frame frame = frames[i];
                // Only the streamer's own pane carries the "Live in" countdown.
                clipped(g, StreamerBoxOcclusion.Rect.of(pane.box(), BORDER), behind,
                    () -> draw(g, source, frame, pane.box(), !pane.second(), mc.player.tickCount, partial));
            }
            Box lowest = panes.get(panes.size() - 1).box();
            clipped(g, StreamerBoxOcclusion.Rect.of(LiveStreamSettingsButton.layout(lowest), 0), behind,
                () -> LiveStreamSettingsButton.draw(g, lowest));
        });
    }

    /**
     * What covers the streamer's box: the inventory's parts while it is open and the cursor is not on the box
     * (or is on a backpack panel); null when the box is drawn whole, on top.
     */
    @Nullable
    private static java.util.List<StreamerBoxOcclusion.Rect> behindInventory(Minecraft mc, java.util.List<HeadViewerPanes.Pane> panes) {
        if (!LiveStreamSettingsButton.showsOn(mc.screen)) return null;
        StreamerBoxOcclusion.Layers layers = StreamerBoxOcclusion.occluders(mc.screen);
        var window = mc.getWindow();
        double mx = mc.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth();
        double my = mc.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight();
        boolean onBox = LiveStreamSettingsButton.contains(LiveStreamSettingsButton.layout(panes.get(panes.size() - 1).box()), mx, my);
        for (HeadViewerPanes.Pane pane : panes) onBox |= contains(pane.box(), mx, my);
        return onBox && !layers.overBackpack(mx, my) ? null : layers.all();
    }

    /** Runs {@code paint} only over the parts of {@code area} no occluder covers; all of it when there are none. */
    private static void clipped(GuiGraphics g, StreamerBoxOcclusion.Rect area,
                                @Nullable java.util.List<StreamerBoxOcclusion.Rect> occluders, Runnable paint) {
        if (occluders == null) { paint.run(); return; }
        for (StreamerBoxOcclusion.Rect r : StreamerBoxOcclusion.visible(area, occluders)) {
            g.enableScissor(r.x0(), r.y0(), r.x1(), r.y1());
            paint.run();
            g.disableScissor();
        }
    }

    /** The streamer's panes: their own stream first, the other under it, as {@link StreamerBoxPanes} says. */
    private static java.util.List<HeadViewerPanes.Pane> streamerPanes(Minecraft mc) {
        LiveFeedSource own = ownSource();
        return StreamerBoxPanes.current(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(),
            LiveFeedClientConfig.headViewerWidth(true), secondLive(), LiveFeedClientConfig.headViewerAtMaxSize(true),
            LiveStreamController.get().streamStartedMs(), LiveFeedClientConfig.streamerBoxStreams(),
            own.ratio(), otherThan(own).ratio());
    }

    /** The stream that is not {@code own}. */
    private static LiveFeedSource otherThan(LiveFeedSource own) {
        return own == LiveFeedSource.SECOND ? LiveFeedSource.MAIN : LiveFeedSource.SECOND;
    }

    /** The lowest pane's box (the first stream's box {@code b} unless a second pane sits under it). */
    private static Box lowestPane(Minecraft mc, Box b) {
        var panes = panes(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), b.w(), secondLive(),
            LiveFeedClientConfig.headViewerAtMaxSize(false));
        return panes.get(panes.size() - 1).box();
    }

    /** The watching panes, each as tall as its own stream's shape needs. */
    private static java.util.List<HeadViewerPanes.Pane> panes(int guiWidth, int guiHeight, int boxWidth, boolean secondLive, boolean atMaxSize) {
        return HeadViewerPanes.current(guiWidth, guiHeight, boxWidth, secondLive, atMaxSize,
            LiveFeedSource.MAIN.ratio(), LiveFeedSource.SECOND.ratio());
    }

    /** The streamer's own picture: the second stream's while they hold the relay's second channel. */
    private static LiveFeedSource ownSource() {
        return LiveStreamController.get().onSecondChannel() ? LiveFeedSource.SECOND : LiveFeedSource.MAIN;
    }

    /** A second stream is live for this viewer (the relay's status carries it). */
    private static boolean secondLive() {
        return LiveStatusPoller.current().second() != null;
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
        if (LiveStreamController.get().streaming()) {
            onStreamerBoxPressed(event, mc);
            return;
        }
        Box b = layout(mc.getWindow().getGuiScaledWidth(), LiveFeedClientConfig.headViewerWidth(false),
            LiveFeedSource.MAIN.ratio());
        Box lowest = lowestPane(mc, b);
        if (LiveTakeoverButton.click(lowest, event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
            return;
        }
        // Two streams, in the inventory: a pane click solos that stream or brings both back (HeadViewerPanes).
        // Where two panes cannot fit (largest size, short window) the click resizes the box as ever.
        int guiWidth = mc.getWindow().getGuiScaledWidth(), guiHeight = mc.getWindow().getGuiScaledHeight();
        if (secondLive() && LiveStreamSettingsButton.showsOn(event.getScreen())
                && HeadViewerPanes.twoFit(guiWidth, guiHeight, b.w(), LiveFeedClientConfig.headViewerAtMaxSize(false),
                    LiveFeedSource.MAIN.ratio(), LiveFeedSource.SECOND.ratio())) {
            var panes = panes(guiWidth, guiHeight, b.w(), true, false);
            if (HeadViewerPanes.apply(panes, event.getMouseX(), event.getMouseY(), System.currentTimeMillis())) {
                event.setCanceled(true);
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
                // The TV on the head remembers the choice (TvStreamChoicePacket → TV_STREAM).
                games.brennan.dungeontrain.net.DungeonTrainNet.sendToServer(
                    new games.brennan.dungeontrain.net.TvStreamChoicePacket(HeadViewerPanes.memoryOf(HeadViewerPanes.choice())));
                return;
            }
        }
        if (!contains(b, event.getMouseX(), event.getMouseY())) return;
        event.setCanceled(true);
        LiveFeedClientConfig.cycleHeadViewerSize(false);
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    /**
     * The streamer's box: the settings cog under the lowest pane opens Stream options; with someone else
     * live, in the inventory, a pane click switches between their own stream, the other and both
     * ({@link StreamerBoxPanes}); otherwise a click on the first pane cycles the size.
     */
    private static void onStreamerBoxPressed(ScreenEvent.MouseButtonPressed.Pre event, Minecraft mc) {
        var panes = streamerPanes(mc);
        if (panes.isEmpty()) return; // "None": nothing on screen to click
        double x = event.getMouseX(), y = event.getMouseY();
        // A backpack panel stays in front of the box, so it gets the click.
        if (LiveStreamSettingsButton.showsOn(event.getScreen())
                && StreamerBoxOcclusion.occluders(event.getScreen()).overBackpack(x, y)) return;
        if (LiveStreamSettingsButton.click(event.getScreen(), panes.get(panes.size() - 1).box(), x, y)) {
            event.setCanceled(true);
            return;
        }
        LiveFeedSource own = ownSource();
        int guiWidth = mc.getWindow().getGuiScaledWidth(), guiHeight = mc.getWindow().getGuiScaledHeight();
        int width = LiveFeedClientConfig.headViewerWidth(true);
        if (secondLive() && LiveStreamSettingsButton.showsOn(event.getScreen())
                && HeadViewerPanes.twoFit(guiWidth, guiHeight, width, LiveFeedClientConfig.headViewerAtMaxSize(true),
                    own.ratio(), otherThan(own).ratio())
                && StreamerBoxPanes.apply(panes, x, y, LiveStreamController.get().streamStartedMs(),
                    LiveFeedClientConfig.streamerBoxStreams())) {
            event.setCanceled(true);
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
            return;
        }
        if (!contains(panes.get(0).box(), x, y)) return;
        event.setCanceled(true);
        LiveFeedClientConfig.cycleHeadViewerSize(true);
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

    /**
     * One pane: {@code source}'s {@code frame} in box {@code b}. {@code primary} panes (the first stream's,
     * or the streamer's own preview) also carry the "Live in" countdown and the takeover vote banner.
     * The picture is the whole frame at its own shape ({@link LiveFeedSource#fullPicture}), which the box
     * was laid out to match.
     */
    private static void draw(GuiGraphics g, LiveFeedSource source, LiveFeedSource.Frame frame, Box b, boolean primary,
                             int tick, float partial) {
        LiveStreamController streamer = LiveStreamController.get();
        boolean streaming = streamer.streaming();
        Font font = Minecraft.getInstance().font;

        g.fill(b.x() - BORDER, b.y() - BORDER, b.x() + b.w() + BORDER, b.y() + b.h() + BORDER, BORDER_COLOR);
        switch (frame.kind()) {
            case PICTURE -> {
                LiveFeedSource.Picture whole = source.fullPicture(tick, partial);
                g.blit(whole != null ? whole.texture() : source.frameTexture(), b.x(), b.y(), b.w(), b.h(), 0f, 0f, 1, 1, 1, 1);
            }
            case STATIC -> noise(g, b);
            case NO_FFMPEG -> g.blit(LiveFeedSource.NO_FFMPEG_TEXTURE, b.x(), b.y(), b.w(), b.h(), 0f, 0f, 1, 1, 1, 1);
            case DOWNLOADING -> {
                g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), DARK);
                downloadingLabel(g, b, font, frame.progress());
            }
            case WAITING -> {
                // First frames still decoding: the same three dots a stalled picture shows.
                g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), DARK);
                bufferingMark(g, b, bufferingDot(System.currentTimeMillis()));
            }
        }
        if (frame.kind() == LiveFeedSource.Kind.PICTURE && frame.buffering()) {
            bufferingMark(g, b, bufferingDot(System.currentTimeMillis()));
        }

        if (!primary) return;
        if (streaming && !showingOwnBroadcast(frame, streamer.playlistUrl())) {
            int n = countdownSeconds(streamer.streamStartedMs(), streamer.segmentSeconds(),
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
        int pad = LABEL_PAD;
        // Shrunk to fit a narrow box (the streamer's own is smaller), so the seconds are never cut off.
        float scale = fitScale(font.width(text), b.w(), pad);
        int h = Math.round(font.lineHeight * scale) + 2 * pad;
        g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + h, BLACKOUT);
        centred(g, font, text, b, b.y() + pad + 0.5f, scale);
    }

    /** The scale that fits {@code textWidth} inside a box {@code boxWidth} wide with {@code pad} each side; at most 1. */
    static float fitScale(int textWidth, int boxWidth, int pad) {
        return Math.max(0.1f, Math.min(1f, (boxWidth - 2f * pad) / Math.max(1, textWidth)));
    }

    /** Draws {@code text} centred across the box at {@code y}, scaled by {@code scale}. */
    private static void centred(GuiGraphics g, Font font, Component text, Box b, float y, float scale) {
        float w = font.width(text) * scale;
        g.pose().pushPose();
        g.pose().translate(b.x() + (b.w() - w) / 2f, y, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, text, 0, 0, 0xFFFFFFFF, true);
        g.pose().popPose();
    }

    /**
     * Vista is still fetching ffmpeg: "Getting video tools" over the percentage when Vista reports
     * one, else over the stepping dots, so the box is never a bare dark rectangle.
     */
    private static void downloadingLabel(GuiGraphics g, Box b, Font font, int progress) {
        float scale = fitScale(font.width(DOWNLOADING), b.w(), LABEL_PAD);
        int labelH = Math.round(font.lineHeight * scale);
        if (progress < 0) {
            centred(g, font, DOWNLOADING, b, b.y() + (b.h() - labelH) / 2f, scale);
            bufferingMark(g, b, bufferingDot(System.currentTimeMillis()));
            return;
        }
        Component pct = Component.literal(progress + "%");
        float pctScale = Math.min(NUMBER_SCALE, fitScale(font.width(pct), b.w(), LABEL_PAD));
        int pctH = Math.round(font.lineHeight * pctScale);
        int gap = 3;
        float top = b.y() + (b.h() - labelH - gap - pctH) / 2f;
        centred(g, font, DOWNLOADING, b, top, scale);
        centred(g, font, pct, b, top + labelH + gap, pctScale);
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
