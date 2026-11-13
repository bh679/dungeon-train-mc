package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.chat.ChatClick;
import games.brennan.dungeontrain.client.ChatClickActions;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpegManager;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Tells the player why a TV shows the "needs FFmpeg" card instead of a broadcast, once per launch,
 * with a button that installs it ({@link FfmpegInstaller}). Also keeps {@link FfmpegSupport#watch}
 * running from the first tick, so a failed Vista download gets DT's copy even before any TV is seen.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class FfmpegHint {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** The buttons' action ({@link ChatClick}): {@code ffmpeg/install} or {@code ffmpeg/folder}. */
    static final String CLICK_ACTION = "ffmpeg";
    private static final String KEY = "chat.dungeontrain.live.ffmpeg.";
    /** A TV block this close (or a TV in hand) counts as seen. */
    private static final double NEAR_SQ = 16.0 * 16.0;

    private static boolean posted;

    private FfmpegHint() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        FfmpegSupport.watch();
    }

    /** Render thread: a TV drew the "needs FFmpeg" card; {@code tvPos} is null for a TV in hand. */
    static void shown(@Nullable BlockPos tvPos) {
        if (posted || FfmpegInstaller.running()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (tvPos != null && mc.player.distanceToSqr(tvPos.getCenter()) > NEAR_SQ) return;
        posted = true;
        mc.player.displayClientMessage(hint(), false);
    }

    private static Component hint() {
        return Component.translatable(KEY + "hint").withStyle(ChatFormatting.GOLD)
            .append(" ")
            .append(installButton());
    }

    /** The green "[Set up FFmpeg]" chat button: the TV hint's, and the Live Eye's no-broadcast line's. */
    static Component installButton() {
        return Component.translatable(KEY + "button")
            .withStyle(s -> s.withColor(ChatFormatting.GREEN).withUnderlined(true)
                .withClickEvent(ChatClick.event(CLICK_ACTION, "install"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.translatable(KEY + "button.hover"))));
    }

    /**
     * Starts the install, as the chat button does: the Stream tab's Download FFmpeg button. Says
     * "ready" if it already is, and nothing while an install runs.
     */
    public static void startInstall() {
        if (FfmpegInstaller.running()) return;
        if (FfmpegSupport.state() == FfmpegSupport.State.READY) {
            say("ready", ChatFormatting.GREEN);
            return;
        }
        if (!FFmpegManager.hasManagedBinaries()) say("installing", ChatFormatting.GRAY);
        FfmpegInstaller.install();
    }

    private static void say(String key, ChatFormatting colour) {
        say(Component.translatable(KEY + key).withStyle(colour));
    }

    private static void say(Component line) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(line, false);
    }

    private static MutableComponent retry(String key) {
        return Component.translatable(KEY + key).withStyle(ChatFormatting.RED)
            .append(" ")
            .append(Component.translatable(KEY + "button.retry")
                .withStyle(s -> s.withColor(ChatFormatting.GREEN).withUnderlined(true)
                    .withClickEvent(ChatClick.event(CLICK_ACTION, "install"))));
    }

    /** The way out when the download can't work on this network: only where DT has a zip to point at. */
    private static Component manual(FfmpegMirror mirror) {
        MutableComponent zip = Component.translatable(KEY + "button.zip")
            .withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, mirror.url()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(mirror.url()))));
        MutableComponent folder = Component.translatable(KEY + "button.folder")
            .withStyle(s -> s.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(ChatClick.event(CLICK_ACTION, "folder"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.literal(FfmpegInstaller.BIN.toString()))));
        return Component.translatable(KEY + "manual").withStyle(ChatFormatting.GRAY)
            .append(" ")
            .append(zip)
            .append(" ")
            .append(folder);
    }

    private static void sayFailed(FfmpegInstaller.Failure failure) {
        if (failure == FfmpegInstaller.Failure.WONT_RUN) {
            say(retry("failed_run"));
            return;
        }
        say(retry("failed"));
        FfmpegMirror mirror = FfmpegMirror.current();
        if (mirror != null) say(manual(mirror));
    }

    private static void openFolder() {
        try {
            Files.createDirectories(FfmpegInstaller.BIN);
            Util.getPlatform().openPath(FfmpegInstaller.BIN.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Could not open {}: {}", FfmpegInstaller.BIN.toAbsolutePath(), e.toString());
        }
    }

    /** Wire the button and the install outcome lines ({@code DungeonTrainClient.clientSetup}). */
    public static void registerClicks() {
        FfmpegInstaller.setListener(new FfmpegInstaller.Listener() {
            @Override public void installed() { say("ready", ChatFormatting.GREEN); }
            @Override public void failed(FfmpegInstaller.Failure failure) { sayFailed(failure); }
        });
        ChatClickActions.register(CLICK_ACTION, args -> {
            if (args.isEmpty()) return;
            if (args.get(0).equals("folder")) {
                openFolder();
                return;
            }
            if (args.get(0).equals("install")) startInstall();
        });
    }
}
