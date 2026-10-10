package games.brennan.dungeontrain.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side Live Feed settings ({@code config/dungeontrain-live-client.toml}): how the streamer's
 * view is captured and encoded, and how a viewer's TVs decode it. None of this is fair-play
 * relevant — it is picture quality against CPU — so it lives apart from the governed configs.
 */
public final class LiveFeedClientConfig {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue STREAMING_ENABLED;
    public static final ModConfigSpec.IntValue CAPTURE_WIDTH;
    public static final ModConfigSpec.IntValue CAPTURE_FPS;
    public static final ModConfigSpec.IntValue BITRATE_KBPS;
    public static final ModConfigSpec.IntValue SEGMENT_SECONDS;
    public static final ModConfigSpec.BooleanValue VIEWING_ENABLED;
    public static final ModConfigSpec.IntValue VIEWER_WIDTH;
    public static final ModConfigSpec.BooleanValue HEAD_VIEWER_ENABLED;
    public static final ModConfigSpec.IntValue HEAD_VIEWER_WIDTH;
    public static final ModConfigSpec.IntValue HEAD_VIEWER_SIZE_WATCHING;
    public static final ModConfigSpec.IntValue HEAD_VIEWER_SIZE_STREAMING;

    /** The pinned feed's sizes, as fractions of {@code headViewerWidth}: two smaller, normal, one bigger. */
    static final float[] HEAD_VIEWER_SIZES = {0.5f, 0.75f, 1f, 1.5f};
    static final int NORMAL_SIZE = 2;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("liveFeed");
        STREAMING_ENABLED = b
            .comment("Allow this client to broadcast when you put the headpiece on. Off = the headpiece still burns away and takes the channel, but nothing is sent (the feed shows static).")
            .define("streamingEnabled", true);
        CAPTURE_WIDTH = b
            .comment("Width of the broadcast picture in pixels (16:9). 1280 = 720p. Lower if encoding costs you frame rate.")
            .defineInRange("captureWidth", 1280, 320, 1920);
        CAPTURE_FPS = b
            .comment("Frames per second sent to the encoder. The game keeps rendering at full speed; this only caps how often a frame is copied out.")
            .defineInRange("captureFps", 20, 5, 30);
        BITRATE_KBPS = b
            .comment("Video bitrate in kbit/s. 2500 is clean 720p; viewers download this much.")
            .defineInRange("bitrateKbps", 2500, 500, 8000);
        SEGMENT_SECONDS = b
            .comment("Seconds of video per uploaded segment. Viewers run 1-2 segments behind. Longer = fewer uploads (cheaper), more delay.")
            .defineInRange("segmentSeconds", 10, 2, 30);
        VIEWING_ENABLED = b
            .comment("Decode the live feed on Live Feed antennas in your world. Off = those TVs show static.")
            .define("viewingEnabled", true);
        VIEWER_WIDTH = b
            .comment("Width the feed is decoded at for your TVs (16:9). 640 is plenty for a block screen; higher costs CPU.")
            .defineInRange("viewerWidth", 640, 160, 1280);
        HEAD_VIEWER_ENABLED = b
            .comment("Pin the live feed to the top-right of your screen while you wear the camcorder or a TV. The Live Viewer hotkey flips this.")
            .define("headViewerEnabled", true);
        HEAD_VIEWER_WIDTH = b
            .comment("Width of the pinned live feed at its normal size, in GUI pixels (16:9). Clicking the feed in the inventory cycles half, three-quarter, normal and one-and-a-half of this.")
            .defineInRange("headViewerWidth", 160, 80, 480);
        HEAD_VIEWER_SIZE_WATCHING = b
            .comment("Pinned feed size while you watch (wearing a TV): 0 = half, 1 = three-quarter, 2 = normal, 3 = one-and-a-half. Set by clicking the feed in the inventory.")
            .defineInRange("headViewerSizeWatching", NORMAL_SIZE, 0, HEAD_VIEWER_SIZES.length - 1);
        HEAD_VIEWER_SIZE_STREAMING = b
            .comment("Pinned feed size while you stream (wearing the camcorder) — smaller by default, since it covers your own view. Same scale as headViewerSizeWatching.")
            .defineInRange("headViewerSizeStreaming", NORMAL_SIZE - 1, 0, HEAD_VIEWER_SIZES.length - 1);
        b.pop();
        SPEC = b.build();
    }

    private LiveFeedClientConfig() {}

    private static boolean loaded() {
        return SPEC.isLoaded();
    }

    public static boolean streamingEnabled() { return !loaded() || STREAMING_ENABLED.get(); }
    public static int captureWidth() { return loaded() ? CAPTURE_WIDTH.get() : 1280; }
    public static int captureFps() { return loaded() ? CAPTURE_FPS.get() : 20; }
    public static int bitrateKbps() { return loaded() ? BITRATE_KBPS.get() : 2500; }
    public static int segmentSeconds() { return loaded() ? SEGMENT_SECONDS.get() : 10; }
    public static boolean viewingEnabled() { return !loaded() || VIEWING_ENABLED.get(); }
    public static int viewerWidth() { return loaded() ? VIEWER_WIDTH.get() : 640; }
    public static boolean headViewerEnabled() { return !loaded() || HEAD_VIEWER_ENABLED.get(); }
    public static int headViewerWidth() { return loaded() ? HEAD_VIEWER_WIDTH.get() : 160; }

    /** Hotkey: flip the pinned viewer and persist it. Returns the new value. */
    public static boolean toggleHeadViewer() {
        boolean next = !headViewerEnabled();
        if (loaded()) {
            HEAD_VIEWER_ENABLED.set(next);
            HEAD_VIEWER_ENABLED.save();
        }
        return next;
    }

    /** The pinned feed's size index for the current mode. */
    public static int headViewerSize(boolean streaming) {
        if (!loaded()) return streaming ? NORMAL_SIZE - 1 : NORMAL_SIZE;
        return (streaming ? HEAD_VIEWER_SIZE_STREAMING : HEAD_VIEWER_SIZE_WATCHING).get();
    }

    /** The pinned feed's width now, in GUI pixels. */
    public static int headViewerWidth(boolean streaming) {
        return sizeWidth(headViewerWidth(), headViewerSize(streaming));
    }

    /** Click in the inventory: step this mode's size up, wrapping from the biggest to the smallest; persisted. */
    public static int cycleHeadViewerSize(boolean streaming) {
        int next = nextSize(headViewerSize(streaming));
        if (loaded()) {
            ModConfigSpec.IntValue v = streaming ? HEAD_VIEWER_SIZE_STREAMING : HEAD_VIEWER_SIZE_WATCHING;
            v.set(next);
            v.save();
        }
        return next;
    }

    static int nextSize(int index) {
        return (Math.floorMod(index, HEAD_VIEWER_SIZES.length) + 1) % HEAD_VIEWER_SIZES.length;
    }

    /** {@code base} scaled to size {@code index}, rounded to an even width. */
    static int sizeWidth(int base, int index) {
        int i = Math.max(0, Math.min(HEAD_VIEWER_SIZES.length - 1, index));
        int w = Math.round(base * HEAD_VIEWER_SIZES[i]);
        return w % 2 == 0 ? w : w + 1;
    }

    /** 16:9 height for a width, rounded to an even number (yuv420p needs even dimensions). */
    public static int heightFor(int width) {
        int h = Math.round(width * 9f / 16f);
        return h % 2 == 0 ? h : h + 1;
    }
}
