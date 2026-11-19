package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * DT's own copy of ffmpeg, for when Vista's download fails (its default source is unreachable from
 * some networks). One zip per platform, served from brennan.games and pinned by size and SHA-256:
 * {@code scripts/live/ffmpeg-mirror/build.sh} builds it and prints both.
 */
public record FfmpegMirror(String url, String sha256, long size) {

    static final String BASE = "https://brennan.games/dungeontrain/tools/ffmpeg/";

    /** Gyan's FFmpeg 9.0.2 essentials build (the one Vista installs on Windows), ffmpeg + ffprobe only. */
    static final FfmpegMirror WINDOWS_X64 = new FfmpegMirror(BASE + "windows-x64.zip",
        "519a41305f37ace4649d7b7db79fdc514019d002fe6843f75147acb57f406fab", 77_388_220L);

    /** Dev builds only: {@code -Ddungeontrain.ffmpegMirror=<url>,<sha256>,<size>} tests the mirror path on any platform. */
    static final String DEV_OVERRIDE_PROPERTY = "dungeontrain.ffmpegMirror";

    /** The mirror for this machine, or null where DT has none and only Vista's own sources apply. */
    @Nullable
    public static FfmpegMirror current() {
        if (DungeonTrain.isDevBuild()) {
            FfmpegMirror dev = parse(System.getProperty(DEV_OVERRIDE_PROPERTY));
            if (dev != null) return dev;
        }
        return forPlatform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /** {@code <url>,<sha256>,<size>}, or null when absent or malformed. */
    @Nullable
    static FfmpegMirror parse(@Nullable String spec) {
        if (spec == null) return null;
        String[] parts = spec.split(",");
        if (parts.length != 3 || !parts[1].matches("[0-9a-fA-F]{64}")) return null;
        try {
            return new FfmpegMirror(parts[0].trim(), parts[1].toLowerCase(Locale.ROOT), Long.parseLong(parts[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    static FfmpegMirror forPlatform(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        if (os.startsWith("windows") && (arch.equals("amd64") || arch.equals("x86_64"))) return WINDOWS_X64;
        return null;
    }
}
