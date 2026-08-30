package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.net.BuilderCreatorResultsPacket;
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
    private static volatile Consumer<BuilderCreatorResultsPacket> creatorListener = null;

    /**
     * Whether My Builds is pointed at the LIVE relay rather than the one this build writes to.
     *
     * <p>A dev-build affordance, and deliberately session-scoped rather than persisted: it starts on
     * this build's own relay every launch, so looking at production data is always something the
     * developer just did rather than something a config file remembered. Cleared with the cached
     * profile on the way out of a world, for the same reason the profile is.</p>
     */
    private static volatile boolean live = false;

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
     * A creator search answered. Not cached, for the same reason a download result is not: it belongs
     * to one query typed into one open screen, and a search screen opened later starts empty.
     */
    public static void creatorResults(BuilderCreatorResultsPacket packet) {
        Consumer<BuilderCreatorResultsPacket> current = creatorListener;
        if (current != null) current.accept(packet);
    }

    /** Listen for creator search results while the search screen is open; null clears it. */
    public static void listenForCreators(Consumer<BuilderCreatorResultsPacket> consumer) {
        creatorListener = consumer;
    }

    /**
     * Drop the cached profile without touching the listeners.
     *
     * <p>What {@link #clear} does on the way out of a world is heavier than a screen wants: switching
     * relay target only invalidates the LIST, and a screen that is still open still needs its
     * listener attached to receive the replacement.</p>
     */
    public static void clearCache() {
        latest = null;
    }

    /** Whether calls from the profile screens should address the live relay. */
    public static boolean live() {
        return live;
    }

    /** Flip the target. The caller re-asks — nothing here refreshes anything by itself. */
    public static void setLive(boolean value) {
        live = value;
    }

    /**
     * Forget the cached profile, so a reopened screen shows "loading" rather than a stale list.
     * Called when leaving a world: the next world may be a different player's.
     */
    public static void clear() {
        latest = null;
        listener = null;
        downloadListener = null;
        creatorListener = null;
        live = false;
    }
}
