package games.brennan.dungeontrain.client.live.deathrec;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.live.FfmpegSupport;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpeg;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The death page's replay, cut from the death recorder's memory a moment after the death: the same
 * shape the Live Feed's {@code LiveDeathClip} makes from a stream (the last seconds before the death
 * and a beat after, silent, {@link #WIDTH} wide), so {@code DeathClipBackground} loops it the same way.
 * No top crop: the stream's clip crops only to hide its name pill, which death recordings leave out.
 * Worker thread.
 */
final class DeathScreenClip {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Seconds kept from the end of the snapshot: about 5 before the death and the beat after it. */
    static final double LENGTH_S = 5.5;
    static final int WIDTH = 640;
    static final long CUT_TIMEOUT_S = 30;

    private DeathScreenClip() {}

    /** ffmpeg's cut: the last {@link #LENGTH_S} of {@code in}, silent, {@link #WIDTH} wide, small and quick. */
    static String[] cutArgs(Path in, Path out) {
        return new String[] {"-hide_banner", "-loglevel", "error", "-nostdin", "-y",
            "-sseof", String.format(Locale.ROOT, "-%.3f", LENGTH_S), "-i", in.toAbsolutePath().toString(),
            "-an", "-vf", "scale=" + WIDTH + ":-2", "-c:v", "libx264", "-preset", "ultrafast",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", out.toAbsolutePath().toString()};
    }

    /** Cuts {@code ts} (the recording so far) into {@code out}; null (logged) when it could not. Blocking. */
    @Nullable
    static Path cut(FFmpeg ffmpeg, byte[] ts, Path out) {
        Path dir = out.getParent();
        Path raw = dir.resolve("death-local.ts");
        Path tmp = dir.resolve("death-local.tmp.mp4");
        try {
            Files.createDirectories(dir);
            Files.write(raw, ts);
            Process p = ffmpeg.runFFmpeg(cutArgs(raw, tmp));
            FfmpegSupport.drain(p.getInputStream(), "death screen clip");
            FfmpegSupport.drain(p.getErrorStream(), "death screen clip");
            if (!p.waitFor(CUT_TIMEOUT_S, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                LOGGER.warn("[DungeonTrain] death screen clip cut timed out");
                return null;
            }
            if (p.exitValue() != 0 || !Files.isRegularFile(tmp) || Files.size(tmp) == 0) {
                LOGGER.warn("[DungeonTrain] death screen clip cut failed (exit {})", p.exitValue());
                return null;
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return out;
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] death screen clip failed: {}", e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            try {
                Files.deleteIfExists(raw);
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
