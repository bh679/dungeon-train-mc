package games.brennan.dungeontrain.config;

import games.brennan.dungeontrain.client.live.LiveViewerMutes.Muted;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side settings for watching the Live Feed on TVs ({@code config/dungeontrain-live-viewer-client.toml}):
 * whether TVs play a stream's sound, and which streamers this viewer has muted. Its own file, apart from
 * {@link LiveFeedClientConfig} (the streamer's capture and encode knobs), so the two never collide.
 */
public final class LiveViewerClientConfig {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue TV_STREAM_SOUND;
    public static final ModConfigSpec.BooleanValue TV_SOUND_ON_TRAIN;
    public static final ModConfigSpec.IntValue STREAM_VOLUME_PERCENT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> MUTED_STREAMERS;

    /** Stream volume starts at full, so nobody's TVs change loudness until they move the slider. */
    static final int STREAM_VOLUME_DEFAULT = 100;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("liveViewer");
        TV_STREAM_SOUND = b
            .comment("Play a live stream's sound from the TVs showing it, and from a TV you wear or hold. Off = every TV is silent. Volume follows streamVolumePercent, Vista's TV volume and the Blocks slider.")
            .define("tvStreamSound", true);
        STREAM_VOLUME_PERCENT = b
            .comment("How loud live streams play from TVs, percent, on top of Vista's TV volume and the Blocks slider. 0 = silent. Also on Music & Sounds. Applies at once.")
            .defineInRange("streamVolumePercent", STREAM_VOLUME_DEFAULT, 0, 100);
        TV_SOUND_ON_TRAIN = b
            .comment("Play live stream sound from the TVs placed on the train. Off = placed TVs are silent; a TV you wear or hold still plays. Only matters while tvStreamSound is on.")
            .define("tvSoundOnTrain", true);
        MUTED_STREAMERS = b
            .comment("Streamers whose sound TVs never play, whenever they stream, as \"<uuid>|<name>\" (the uuid may be empty). Managed in-game: Options > Dungeon Train > Stream > Muted streamers, or the Mute buttons in Live chat.")
            .defineListAllowEmpty("mutedStreamers", () -> List.<String>of(), () -> "|Steve",
                o -> o instanceof String s && Muted.parse(s) != null);
        b.pop();
        SPEC = b.build();
    }

    private LiveViewerClientConfig() {}

    private static boolean loaded() {
        return SPEC.isLoaded();
    }

    /** Whether TVs play stream sound; on until the config loads. */
    public static boolean tvStreamSound() { return !loaded() || TV_STREAM_SOUND.get(); }

    public static void setTvStreamSound(boolean value) {
        if (!loaded() || TV_STREAM_SOUND.get() == value) return;
        TV_STREAM_SOUND.set(value);
        TV_STREAM_SOUND.save();
    }

    /** How loud streams play from TVs, 0 to 100; full until the config loads. */
    public static int streamVolumePercent() { return loaded() ? STREAM_VOLUME_PERCENT.get() : STREAM_VOLUME_DEFAULT; }

    public static void setStreamVolumePercent(int value) {
        int v = Math.max(0, Math.min(100, value));
        if (!loaded() || STREAM_VOLUME_PERCENT.get() == v) return;
        STREAM_VOLUME_PERCENT.set(v);
        STREAM_VOLUME_PERCENT.save();
    }

    /** Whether the TVs placed on the train play stream sound; on until the config loads. */
    public static boolean tvSoundOnTrain() { return !loaded() || TV_SOUND_ON_TRAIN.get(); }

    public static void setTvSoundOnTrain(boolean value) {
        if (!loaded() || TV_SOUND_ON_TRAIN.get() == value) return;
        TV_SOUND_ON_TRAIN.set(value);
        TV_SOUND_ON_TRAIN.save();
    }

    /** The muted streamers, oldest first; empty until the config loads. */
    public static List<Muted> mutedStreamers() {
        if (!loaded()) return List.of();
        return parse(MUTED_STREAMERS.get());
    }

    public static void setMutedStreamers(List<Muted> muted) {
        if (!loaded()) return;
        MUTED_STREAMERS.set(serialize(muted));
        MUTED_STREAMERS.save();
    }

    /** Config entries to streamers, skipping any that do not parse. Pure, for the tests. */
    static List<Muted> parse(List<? extends String> entries) {
        List<Muted> out = new ArrayList<>(entries.size());
        for (String e : entries) {
            Muted m = Muted.parse(e);
            if (m != null) out.add(m);
        }
        return List.copyOf(out);
    }

    /** Streamers to config entries. Pure, for the tests. */
    static List<String> serialize(List<Muted> muted) {
        List<String> out = new ArrayList<>(muted.size());
        for (Muted m : muted) out.add(m.serialize());
        return List.copyOf(out);
    }
}
