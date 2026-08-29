package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.net.BuilderProfileDownloadResultPacket;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.Consumer;

/**
 * Client-side holder for the player's relay build profile.
 *
 * <p>The profile arrives asynchronously — the screen asks, the server asks the relay, and the answer
 * lands whenever it lands — so it cannot be a return value. The screen registers a listener while it
 * is open and drops it on close; a reply that arrives after the screen has gone updates the cache and
 * finds nobody listening, which is the correct outcome rather than a leak.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderProfileState {

    private static volatile BuilderProfilePacket latest = null;
    private static volatile Consumer<BuilderProfilePacket> listener = null;
    private static volatile Consumer<BuilderProfileDownloadResultPacket> downloadListener = null;

    private BuilderProfileState() {}

    public static void accept(BuilderProfilePacket packet) {
        latest = packet;
        Consumer<BuilderProfilePacket> current = listener;
        if (current != null) current.accept(packet);
    }

    /** The last profile received, or null when none has arrived this session. */
    public static BuilderProfilePacket latest() {
        return latest;
    }

    public static List<BuilderProfilePacket.Entry> builds() {
        BuilderProfilePacket packet = latest;
        return packet == null ? List.of() : packet.builds();
    }

    /** Listen while a screen is open. Passing null clears it — every screen must do so on close. */
    public static void listen(Consumer<BuilderProfilePacket> consumer) {
        listener = consumer;
    }

    /**
     * A download finished. Not cached, unlike the profile: it is an answer to one press of one
     * button, and a screen reopened later should show what is on the relay now rather than replay
     * what happened last time.
     */
    public static void downloadResult(BuilderProfileDownloadResultPacket packet) {
        Consumer<BuilderProfileDownloadResultPacket> current = downloadListener;
        if (current != null) current.accept(packet);
    }

    /** Listen for download outcomes while a screen is open; null clears it, as {@link #listen} does. */
    public static void listenForDownloads(Consumer<BuilderProfileDownloadResultPacket> consumer) {
        downloadListener = consumer;
    }

    /**
     * Forget the cached profile, so a reopened screen shows "loading" rather than a stale list.
     * Called when leaving a world: the next world may be a different player's.
     */
    public static void clear() {
        latest = null;
        listener = null;
        downloadListener = null;
    }
}
