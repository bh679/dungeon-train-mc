package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.net.BuilderCreatorResultsPacket;
import games.brennan.dungeontrain.net.BuilderFavouritesPacket;
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
    private static volatile BuilderFavouritesPacket favourites = null;
    private static volatile Consumer<BuilderFavouritesPacket> favouritesListener = null;

    /**
     * Whether My Builds is pointed at the LIVE relay rather than the one this build writes to.
     *
     * <p>A dev-build affordance, and deliberately session-scoped rather than persisted: it starts on
     * this build's own relay every launch, so looking at production data is always something the
     * developer just did rather than something a config file remembered. Cleared with the cached
     * profile on the way out of a world, for the same reason the profile is.</p>
     */
    private static volatile boolean live = false;

    /**
     * The builder whose profile My Builds last showed — {@code ""} for the player's own.
     *
     * <p>Remembered so that reopening the screen comes back to whoever was being looked at, rather
     * than snapping home and making the search a toll on every visit: looking at somebody's builds is
     * usually several trips, not one. Session-scoped like {@link #live}, and reset by the same two
     * events — leaving a world, and switching relay, where the remembered uuid may name a builder the
     * other pool has never heard of.</p>
     */
    private static volatile String viewedUuid = "";
    private static volatile String viewedName = "";

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
     * The favourites list arrived. Cached, unlike a search or a download result: a star set in My
     * Builds has to show on the Favourites screen without a round trip, and the screen reopens often
     * enough that an instant first paint is worth a stale one second.
     */
    public static void acceptFavourites(BuilderFavouritesPacket packet) {
        favourites = packet;
        Consumer<BuilderFavouritesPacket> current = favouritesListener;
        if (current != null) current.accept(packet);
    }

    /** The last favourites list received, or null when none has arrived this session. */
    public static BuilderFavouritesPacket favourites() {
        return favourites;
    }

    /** Listen for favourites while a screen is open; null clears it, as {@link #listen} does. */
    public static void listenForFavourites(Consumer<BuilderFavouritesPacket> consumer) {
        favouritesListener = consumer;
    }

    /**
     * Note locally that one build's star has been flipped, so a screen redrawn before the relay
     * answers shows what the player just did.
     *
     * <p>The star is set optimistically — the packet is fire-and-forget and the truth comes back on
     * the next listing — and this is what keeps the CACHED profile agreeing with the tile the player
     * is looking at. Without it, closing and reopening My Builds inside one session would show the
     * star snapping back, which reads as the click having failed when it did not.</p>
     */
    public static void noteFavourite(int relayId, boolean favourite) {
        BuilderProfilePacket packet = latest;
        if (packet == null) return;
        List<BuilderProfilePacket.Entry> updated = new java.util.ArrayList<>(packet.builds().size());
        boolean changed = false;
        for (BuilderProfilePacket.Entry e : packet.builds()) {
            if (e.relayId() == relayId && e.favourite() != favourite) {
                updated.add(new BuilderProfilePacket.Entry(e.relayId(), e.kind(), e.subKind(),
                        e.buildName(), e.published(), e.flag(), e.review(), e.stage(), e.changes(),
                        favourite, e.ownerUuid(), e.ownerName()));
                changed = true;
            } else {
                updated.add(e);
            }
        }
        if (!changed) return;
        latest = new BuilderProfilePacket(packet.status(), List.copyOf(updated),
                packet.ownerUuid(), packet.ownerName(), packet.mine());
        // The favourites LIST is now wrong either way — a star added is a row it lacks, one removed is
        // a row it should lose — and rebuilding it here would mean guessing at rows this cache does not
        // hold. Dropped instead, so the screen re-asks and gets the truth.
        favourites = null;
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
        favourites = null;
    }

    /** Whether calls from the profile screens should address the live relay. */
    public static boolean live() {
        return live;
    }

    /** Flip the target. The caller re-asks — nothing here refreshes anything by itself. */
    public static void setLive(boolean value) {
        live = value;
    }

    /** The remembered builder's uuid, or {@code ""} when My Builds should show the player's own. */
    public static String viewedUuid() {
        return viewedUuid;
    }

    /** What to call the remembered builder — their display name as the search gave it. */
    public static String viewedName() {
        return viewedName;
    }

    /** Remember who is being looked at. Empty uuid means "back to my own builds". */
    public static void setViewed(String uuid, String name) {
        viewedUuid = uuid == null ? "" : uuid;
        viewedName = name == null ? "" : name;
    }

    /**
     * Forget the cached profile, so a reopened screen shows "loading" rather than a stale list.
     * Called when leaving a world: the next world may be a different player's.
     */
    public static void clear() {
        latest = null;
        favourites = null;
        listener = null;
        downloadListener = null;
        creatorListener = null;
        favouritesListener = null;
        live = false;
        setViewed("", "");
    }
}
