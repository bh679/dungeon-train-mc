package games.brennan.dungeontrain.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side Live Feed settings ({@code config/dungeontrain-live-client.toml}): how the streamer's
 * view is captured and encoded, and how a viewer's TVs decode it. None of this is fair-play
 * relevant — it is picture quality against CPU — so it lives apart from the governed configs.
 */
public final class LiveFeedClientConfig {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue STREAMING_ENABLED;
    public static final ModConfigSpec.BooleanValue STREAM_GUI;
    public static final ModConfigSpec.BooleanValue STREAM_CHAT;
    public static final ModConfigSpec.BooleanValue STREAM_AUDIO;
    public static final ModConfigSpec.EnumValue<NameTag> STREAM_NAME_TAG;
    public static final ModConfigSpec.BooleanValue POPUPS_OVER_NAME_TAG;
    public static final ModConfigSpec.BooleanValue LIVE_CHAT;
    public static final ModConfigSpec.BooleanValue LIVE_MOMENT_ADVANCEMENTS;
    public static final ModConfigSpec.BooleanValue LIVE_MOMENT_PHOTOS;
    public static final ModConfigSpec.BooleanValue LIVE_MOMENT_BOOKS;
    public static final ModConfigSpec.BooleanValue TWITCH_SHOUTOUT_HINT;
    public static final ModConfigSpec.IntValue CAPTURE_WIDTH;
    public static final ModConfigSpec.IntValue CAPTURE_FPS;
    public static final ModConfigSpec.IntValue BITRATE_KBPS;
    public static final ModConfigSpec.BooleanValue SAVE_BANDWIDTH;
    public static final ModConfigSpec.IntValue SEGMENT_SECONDS;
    public static final ModConfigSpec.BooleanValue VIEWING_ENABLED;
    public static final ModConfigSpec.IntValue VIEWER_WIDTH;
    public static final ModConfigSpec.BooleanValue HEAD_VIEWER_ENABLED;
    public static final ModConfigSpec.IntValue HEAD_VIEWER_WIDTH;
    public static final ModConfigSpec.IntValue HEAD_VIEWER_SIZE_WATCHING;
    public static final ModConfigSpec.IntValue HEAD_VIEWER_SIZE_STREAMING;

    /** Which of the streamer's names viewers see above the hotbar. */
    public enum NameTag { MINECRAFT, DISCORD, NONE }

    /** The pinned feed's sizes, as fractions of {@code headViewerWidth}: two smaller, normal, one bigger. */
    static final float[] HEAD_VIEWER_SIZES = {0.5f, 0.75f, 1f, 1.5f};
    static final int NORMAL_SIZE = 2;

    /** The Options → Stream video sizes, as broadcast widths: 360p, 480p, 540p, 720p, 1080p (all even). */
    static final List<Integer> CAPTURE_SIZES = List.of(640, 854, 960, 1280, 1920);
    static final int CAPTURE_WIDTH_MIN = 320;
    static final int CAPTURE_WIDTH_MAX = 1920;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("liveFeed");
        STREAMING_ENABLED = b
            .comment("Allow this client to broadcast when you put the headpiece on. Off = the headpiece still burns away and takes the channel, but nothing is sent (the feed shows static).")
            .define("streamingEnabled", true);
        STREAM_GUI = b
            .comment("Show your HUD and menus (inventories, the pause menu, advancements) to viewers. Off = viewers see only the world and your hand. Screens you type into (the chat box, signs, books, anvils) and other mods' screens are never shown.")
            .define("streamGui", true);
        STREAM_CHAT = b
            .comment("Show the chat lines on your screen to viewers, whispers included. What you type is never shown. Always off in Kid mode, whatever this says.")
            .define("streamChat", true);
        STREAM_AUDIO = b
            .comment("Send in-game sounds with your stream: everything you hear from Minecraft and Dungeon Train, music included. Never your microphone, voice chat or other apps. On by default; takes effect from your next stream.")
            .define("streamAudio", true);
        STREAM_NAME_TAG = b
            .comment("Your name on the stream, bottom-middle above the hotbar: MINECRAFT, DISCORD (your linked Discord name; nothing if not linked) or NONE. Always NONE in Kid mode.")
            .defineEnum("streamNameTag", NameTag.MINECRAFT);
        POPUPS_OVER_NAME_TAG = b
            .comment("Advancement and other pop-ups show on top of your name tag on the stream. Off = your name tag stays on top, and the stream skips copying the pop-ups back over it.")
            .define("popupsOverNameTag", true);
        LIVE_CHAT = b
            .comment("Mirror the stream's Discord chat into your in-game chat while a stream is on air (as the streamer, or wearing a TV), and post what you type back to it. Always off in Kid mode, whatever this says.")
            .define("liveChat", true);
        LIVE_MOMENT_ADVANCEMENTS = b
            .comment("While you stream with the Live Eye, say in the stream's Live chat (Discord, Twitch, the website and TV wearers) when you earn an advancement. Always off in Kid mode.")
            .define("liveMomentAdvancements", true);
        LIVE_MOMENT_PHOTOS = b
            .comment("While you stream with the Live Eye, share each photo you take into the stream's Live chat (the picture on Discord and the website). Always off in Kid mode.")
            .define("liveMomentPhotos", true);
        LIVE_MOMENT_BOOKS = b
            .comment("While you stream with the Live Eye, share each book you sign into the stream's Live chat: its title and first lines. Always off in Kid mode.")
            .define("liveMomentBooks", true);
        TWITCH_SHOUTOUT_HINT = b
            .comment("When you go live without a Twitch saved, say in chat that dropping your Twitch username or URL there gets you a shout-out on the official Dungeon Train Twitch. Never shown once a Twitch is saved, or in Kid mode.")
            .define("twitchShoutoutHint", true);
        CAPTURE_WIDTH = b
            .comment("Width of the broadcast picture in pixels (16:9). 1280 = 720p. Lower if encoding costs you frame rate.")
            .defineInRange("captureWidth", 1280, CAPTURE_WIDTH_MIN, CAPTURE_WIDTH_MAX);
        CAPTURE_FPS = b
            .comment("Frames per second sent to the encoder. The game keeps rendering at full speed; this only caps how often a frame is copied out.")
            .defineInRange("captureFps", 20, 5, 30);
        BITRATE_KBPS = b
            .comment("Video bitrate in kbit/s. 2500 is clean 720p; viewers download this much.")
            .defineInRange("bitrateKbps", 2500, 500, 8000);
        SAVE_BANDWIDTH = b
            .comment("Compress the stream harder on your own computer: a slower x264 preset at about half of bitrateKbps, for roughly the same picture with less upload. Costs CPU and so frame rate. Off by default; takes effect from your next stream.")
            .define("saveBandwidth", false);
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
            .comment("Width of the pinned live feed at its normal size, in GUI pixels (16:9). Clicking the feed in the inventory or chat cycles half, three-quarter, normal and one-and-a-half of this.")
            .defineInRange("headViewerWidth", 160, 80, 480);
        HEAD_VIEWER_SIZE_WATCHING = b
            .comment("Pinned feed size while you watch (wearing a TV): 0 = half, 1 = three-quarter, 2 = normal, 3 = one-and-a-half. Set by clicking the feed in the inventory or chat.")
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
    /** Evened here too: a hand-edited odd width would kill libx264's yuv420p output at start. */
    public static int captureWidth() { return loaded() ? evenCaptureWidth(CAPTURE_WIDTH.get()) : 1280; }
    public static int captureFps() { return loaded() ? CAPTURE_FPS.get() : 20; }
    public static int bitrateKbps() { return loaded() ? BITRATE_KBPS.get() : 2500; }
    public static int segmentSeconds() { return loaded() ? SEGMENT_SECONDS.get() : 10; }
    public static boolean viewingEnabled() { return !loaded() || VIEWING_ENABLED.get(); }
    public static int viewerWidth() { return loaded() ? VIEWER_WIDTH.get() : 640; }
    public static boolean headViewerEnabled() { return !loaded() || HEAD_VIEWER_ENABLED.get(); }
    public static int headViewerWidth() { return loaded() ? HEAD_VIEWER_WIDTH.get() : 160; }

    /**
     * Persist whether this client broadcasts when the headpiece goes on. Driven by the first-launch
     * consent card's Livestreaming switch and the Options row; idempotent, no-op pre-load.
     */
    public static void setStreamingEnabled(boolean value) {
        if (!loaded() || STREAMING_ENABLED.get() == value) return;
        STREAMING_ENABLED.set(value);
        STREAMING_ENABLED.save();
    }

    /**
     * Persist whether this client decodes the live feed onto TVs and the pinned viewer. Off = they
     * show static and no decoder runs (the idle watchdog stops one already running). Driven by the
     * Options row on the Stream and Performance tabs; idempotent, no-op pre-load.
     */
    public static void setViewingEnabled(boolean value) {
        if (!loaded() || VIEWING_ENABLED.get() == value) return;
        VIEWING_ENABLED.set(value);
        VIEWING_ENABLED.save();
    }

    /** Whether viewers see the HUD and the listed menus ({@code client.live.StreamableScreens}). */
    public static boolean streamGui() { return !loaded() || STREAM_GUI.get(); }

    /** The chat switch as set; {@code StreamCapture} still forces chat off in Kid mode. */
    public static boolean streamChat() { return !loaded() || STREAM_CHAT.get(); }

    /** Whether the stream carries in-game sounds (never the microphone). On unless the player turns it off. */
    public static boolean streamAudio() { return !loaded() || STREAM_AUDIO.get(); }

    /** Whether the encoder trades CPU for upload (slower preset, lower bitrate). Off unless the player turns it on. */
    public static boolean saveBandwidth() { return loaded() && SAVE_BANDWIDTH.get(); }

    public static void setSaveBandwidth(boolean value) {
        if (!loaded() || SAVE_BANDWIDTH.get() == value) return;
        SAVE_BANDWIDTH.set(value);
        SAVE_BANDWIDTH.save();
    }

    public static void setStreamAudio(boolean value) {
        if (!loaded() || STREAM_AUDIO.get() == value) return;
        STREAM_AUDIO.set(value);
        STREAM_AUDIO.save();
    }

    /** The name tag as set; {@code StreamNameTag} still forces it off in Kid mode. */
    public static NameTag streamNameTag() { return loaded() ? STREAM_NAME_TAG.get() : NameTag.MINECRAFT; }

    public static void setStreamNameTag(NameTag value) {
        if (!loaded() || STREAM_NAME_TAG.get() == value) return;
        STREAM_NAME_TAG.set(value);
        STREAM_NAME_TAG.save();
    }

    /** Pop-ups are copied back over the stream's name tag ({@code LiveToastRects}). On unless turned off. */
    public static boolean popupsOverNameTag() { return !loaded() || POPUPS_OVER_NAME_TAG.get(); }

    public static void setPopupsOverNameTag(boolean value) {
        if (!loaded() || POPUPS_OVER_NAME_TAG.get() == value) return;
        POPUPS_OVER_NAME_TAG.set(value);
        POPUPS_OVER_NAME_TAG.save();
    }

    public static void setStreamGui(boolean value) {
        if (!loaded() || STREAM_GUI.get() == value) return;
        STREAM_GUI.set(value);
        STREAM_GUI.save();
    }

    public static void setStreamChat(boolean value) {
        if (!loaded() || STREAM_CHAT.get() == value) return;
        STREAM_CHAT.set(value);
        STREAM_CHAT.save();
    }

    /** The shout-out reminder at stream start ({@code LiveStreamController}); only matters while no Twitch is saved. */
    public static boolean twitchShoutoutHint() { return !loaded() || TWITCH_SHOUTOUT_HINT.get(); }

    public static void setTwitchShoutoutHint(boolean value) {
        if (!loaded() || TWITCH_SHOUTOUT_HINT.get() == value) return;
        TWITCH_SHOUTOUT_HINT.set(value);
        TWITCH_SHOUTOUT_HINT.save();
    }

    /** The Discord live chat switch as set; {@code LiveChatBridge} still forces it off in Kid mode. */
    public static boolean liveChat() { return !loaded() || LIVE_CHAT.get(); }

    public static void setLiveChat(boolean value) {
        if (!loaded() || LIVE_CHAT.get() == value) return;
        LIVE_CHAT.set(value);
        LIVE_CHAT.save();
    }

    /** Share advancements into Live chat while streaming; {@code LiveMoments} still forces it off in Kid mode. */
    public static boolean liveMomentAdvancements() { return !loaded() || LIVE_MOMENT_ADVANCEMENTS.get(); }

    /** Share photos into Live chat while streaming; {@code LiveMoments} still forces it off in Kid mode. */
    public static boolean liveMomentPhotos() { return !loaded() || LIVE_MOMENT_PHOTOS.get(); }

    /** Share signed books into Live chat while streaming; {@code LiveMoments} still forces it off in Kid mode. */
    public static boolean liveMomentBooks() { return !loaded() || LIVE_MOMENT_BOOKS.get(); }

    public static void setLiveMomentAdvancements(boolean value) { setBool(LIVE_MOMENT_ADVANCEMENTS, value); }

    public static void setLiveMomentPhotos(boolean value) { setBool(LIVE_MOMENT_PHOTOS, value); }

    public static void setLiveMomentBooks(boolean value) { setBool(LIVE_MOMENT_BOOKS, value); }

    private static void setBool(ModConfigSpec.BooleanValue v, boolean value) {
        if (!loaded() || v.get() == value) return;
        v.set(value);
        v.save();
    }

    /**
     * Persist the broadcast width (Options → Stream → Video size). Clamped and evened; the next
     * stream picks it up, and the preview follows it straight away.
     */
    public static void setCaptureWidth(int width) {
        int w = evenCaptureWidth(width);
        if (!loaded() || CAPTURE_WIDTH.get() == w) return;
        CAPTURE_WIDTH.set(w);
        CAPTURE_WIDTH.save();
    }

    /** {@code width} clamped to the allowed range and rounded down to even (yuv420p needs even dimensions). */
    static int evenCaptureWidth(int width) {
        int w = Math.max(CAPTURE_WIDTH_MIN, Math.min(CAPTURE_WIDTH_MAX, width));
        return w - w % 2;
    }

    /** The sizes the Options row offers: the presets, plus {@code current} if it was hand-set off them. */
    public static List<Integer> captureSizeValues(int current) {
        if (CAPTURE_SIZES.contains(current)) return CAPTURE_SIZES;
        List<Integer> values = new ArrayList<>(CAPTURE_SIZES);
        values.add(current);
        values.sort(null);
        return List.copyOf(values);
    }

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

    /** Click in the inventory or chat: step this mode's size up, wrapping from the biggest to the smallest; persisted. */
    public static int cycleHeadViewerSize(boolean streaming) {
        int next = nextSize(headViewerSize(streaming));
        if (loaded()) {
            ModConfigSpec.IntValue v = streaming ? HEAD_VIEWER_SIZE_STREAMING : HEAD_VIEWER_SIZE_WATCHING;
            v.set(next);
            v.save();
        }
        return next;
    }

    /**
     * Options → Stream → Live viewer size: one size for the pinned feed whether you watch or stream.
     * Clicking the feed still steps each mode on its own afterwards. Persisted; no-op pre-load.
     */
    public static void setHeadViewerSize(int index) {
        int i = clampSize(index);
        if (!loaded()) return;
        for (ModConfigSpec.IntValue v : List.of(HEAD_VIEWER_SIZE_WATCHING, HEAD_VIEWER_SIZE_STREAMING)) {
            if (v.get() != i) {
                v.set(i);
                v.save();
            }
        }
    }

    /** The pinned feed's size indexes, smallest first. */
    public static List<Integer> headViewerSizeIndexes() {
        return java.util.stream.IntStream.range(0, HEAD_VIEWER_SIZES.length).boxed().toList();
    }

    /** Size {@code index} as a percentage of the normal width: 50, 75, 100, 150. */
    public static int headViewerSizePercent(int index) {
        return Math.round(HEAD_VIEWER_SIZES[clampSize(index)] * 100);
    }

    static int clampSize(int index) {
        return Math.max(0, Math.min(HEAD_VIEWER_SIZES.length - 1, index));
    }

    static int nextSize(int index) {
        return (Math.floorMod(index, HEAD_VIEWER_SIZES.length) + 1) % HEAD_VIEWER_SIZES.length;
    }

    /** {@code base} scaled to size {@code index}, rounded to an even width. */
    static int sizeWidth(int base, int index) {
        int i = clampSize(index);
        int w = Math.round(base * HEAD_VIEWER_SIZES[i]);
        return w % 2 == 0 ? w : w + 1;
    }

    /** 16:9 height for a width, rounded to an even number (yuv420p needs even dimensions). */
    public static int heightFor(int width) {
        int h = Math.round(width * 9f / 16f);
        return h % 2 == 0 ? h : h + 1;
    }
}
