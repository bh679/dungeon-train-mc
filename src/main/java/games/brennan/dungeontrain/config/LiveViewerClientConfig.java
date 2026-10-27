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
    public static final ModConfigSpec.ConfigValue<List<? extends String>> MUTED_STREAMERS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("liveViewer");
        TV_STREAM_SOUND = b
            .comment("Play a live stream's sound from the TVs showing it, and from a TV you wear or hold. Off = every TV is silent. Volume follows Vista's TV volume and the Blocks slider.")
            .define("tvStreamSound", true);
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
