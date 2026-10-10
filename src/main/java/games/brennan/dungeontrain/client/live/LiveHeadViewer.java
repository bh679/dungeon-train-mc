package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.registry.ModItems;
import net.mehvahdjukaar.vista.common.tv.TVBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
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
 * <p>Clicking the box in the inventory cycles its size ({@link LiveFeedClientConfig#cycleHeadViewerSize});
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

    private static final Component COUNTDOWN = Component.translatable("gui.dungeontrain.live.countdown");
    private static final int BORDER_COLOR = 0xFF101010;
    private static final int DARK = 0xFF181818;
    private static final int BLACKOUT = 0xC8000000;
    private static final Random NOISE = new Random();

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
        if (head.is(ModItems.LIVE_HEADPIECE.get())) return true;
        return head.getItem() instanceof BlockItem bi && bi.getBlock() instanceof TVBlock;
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
            if (!shown(mc) || LiveStreamController.get().streaming() || mc.options.hideGui) return;
            draw(g, LiveFeedSource.MAIN.frame(mc.player.tickCount, delta.getGameTimeDeltaPartialTick(false), false));
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
        LiveRecOverlay.guiPass(g -> draw(g, frame));
    }

    /** In the inventory, a left click on the box cycles its size and goes no further. */
    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT || !isInventory(event.getScreen())) return;
        Minecraft mc = Minecraft.getInstance();
        if (!shown(mc)) return;
        boolean streaming = LiveStreamController.get().streaming();
        Box b = layout(mc.getWindow().getGuiScaledWidth(), LiveFeedClientConfig.headViewerWidth(streaming));
        if (!contains(b, event.getMouseX(), event.getMouseY())) return;
        event.setCanceled(true);
        LiveFeedClientConfig.cycleHeadViewerSize(streaming);
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    static boolean isInventory(Screen screen) {
        return screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen;
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

        if (streaming && !showingOwnBroadcast(frame, streamer.playlistUrl())) {
            int n = countdownSeconds(streamer.streamStartedMs(), LiveFeedClientConfig.segmentSeconds(),
                System.currentTimeMillis());
            countdown(g, b, font, n);
        }
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

    /** Snow for a dark channel — a cheap per-frame grey speckle, not Vista's shader. */
    private static void noise(GuiGraphics g, Box b) {
        for (int y = b.y(); y < b.y() + b.h(); y += NOISE_CELL) {
            int y1 = Math.min(y + NOISE_CELL, b.y() + b.h());
            for (int x = b.x(); x < b.x() + b.w(); x += NOISE_CELL) {
                int v = 40 + NOISE.nextInt(160);
                g.fill(x, y, Math.min(x + NOISE_CELL, b.x() + b.w()), y1, 0xFF000000 | v << 16 | v << 8 | v);
            }
        }
    }
}
