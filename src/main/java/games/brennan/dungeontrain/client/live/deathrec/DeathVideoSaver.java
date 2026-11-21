package games.brennan.dungeontrain.client.live.deathrec;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.live.FfmpegSupport;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpeg;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Turns a held recording into a file in {@code <gameDir>/dungeontrain/death-videos/}: the ring's
 * MPEG-TS is written as it is, then remuxed (no re-encode) into an MP4 that every player opens, and
 * the TS is deleted. If the remux fails or there is no time for it (the game is closing), the TS
 * stays: it is a playable video too. The player is told in chat, with the file name as a link.
 */
final class DeathVideoSaver {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
    static final long REMUX_TIMEOUT_S = 30;

    private DeathVideoSaver() {}

    /** {@code death-2026-11-21_23-58-04}: sorts by time, safe on every file system. */
    static String baseName(LocalDateTime when) {
        return "death-" + STAMP.format(when);
    }

    /** {@code dir/base.ext}, or {@code base-2.ext}, {@code base-3.ext}… when taken (two deaths in one second). */
    static Path freePath(Path dir, String base, String ext) {
        Path p = dir.resolve(base + "." + ext);
        for (int i = 2; Files.exists(p); i++) p = dir.resolve(base + "-" + i + "." + ext);
        return p;
    }

    /**
     * Writes {@code ts} and, with {@code remux}, makes the MP4. Blocking: a worker thread, or the
     * shutdown hook with {@code remux} false. Returns the file kept, or null when nothing could be written.
     */
    @Nullable
    static Path save(Path dir, byte[] ts, LocalDateTime when, @Nullable FFmpeg ffmpeg, boolean remux) {
        String base = baseName(when);
        Path raw;
        try {
            Files.createDirectories(dir);
            raw = freePath(dir, base, "ts");
            Files.write(raw, ts);
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] death video not saved: {}", e.toString());
            return null;
        }
        if (!remux || ffmpeg == null) return raw;
        Path mp4 = freePath(dir, base, "mp4");
        try {
            Process p = ffmpeg.runFFmpeg(new String[] {"-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-i", raw.toString(), "-c", "copy", "-movflags", "+faststart", mp4.toString()});
            FfmpegSupport.drain(p.getErrorStream(), "death-rec-remux");
            FfmpegSupport.drain(p.getInputStream(), "death-rec-remux-out");
            if (!p.waitFor(REMUX_TIMEOUT_S, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                LOGGER.warn("[DungeonTrain] death video remux timed out; keeping {}", raw.getFileName());
                Files.deleteIfExists(mp4);
                return raw;
            }
            if (p.exitValue() != 0 || !Files.isRegularFile(mp4) || Files.size(mp4) == 0) {
                LOGGER.warn("[DungeonTrain] death video remux failed (exit {}); keeping {}", p.exitValue(), raw.getFileName());
                Files.deleteIfExists(mp4);
                return raw;
            }
            Files.deleteIfExists(raw);
            return mp4;
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] death video remux failed: {}; keeping {}", e.toString(), raw.getFileName());
            return raw;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return raw;
        }
    }

    /** Render thread: the chat line for a kept file, or the failure line. */
    static void announce(@Nullable Path file) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (file == null) {
            mc.player.displayClientMessage(Component.translatable("chat.dungeontrain.death_video.failed")
                .withStyle(ChatFormatting.GRAY), false);
            return;
        }
        String absolute = file.toAbsolutePath().toString();
        Component link = Component.literal(file.getFileName().toString())
            .withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, absolute))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.translatable("chat.dungeontrain.death_video.open"))));
        mc.player.displayClientMessage(Component.translatable("chat.dungeontrain.death_video.saved", link)
            .withStyle(ChatFormatting.GRAY), false);
    }
}
