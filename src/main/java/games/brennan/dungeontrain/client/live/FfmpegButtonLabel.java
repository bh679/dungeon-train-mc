package games.brennan.dungeontrain.client.live;

/**
 * What the Stream tab's FFmpeg button says, and whether it can be pressed: only when this client
 * has no FFmpeg and none is on its way. Free of Minecraft types so a test can reach it.
 */
public record FfmpegButtonLabel(String key, int percent, boolean active) {

    private static final String KEY = "gui.dungeontrain.options.ffmpeg.";

    /** {@code percent} is the download's progress, -1 when unknown. */
    public static FfmpegButtonLabel of(FfmpegSupport.State state, boolean installing, int percent) {
        if (state == FfmpegSupport.State.READY) return new FfmpegButtonLabel(KEY + "installed", -1, false);
        if (installing || state == FfmpegSupport.State.DOWNLOADING) {
            return percent < 0
                ? new FfmpegButtonLabel(KEY + "downloading", -1, false)
                : new FfmpegButtonLabel(KEY + "downloading_percent", Math.min(percent, 100), false);
        }
        return new FfmpegButtonLabel(KEY + "download", -1, true);
    }
}
