package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.config.LiveViewerClientConfig;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who a viewer has silenced on the TVs: one stream (this session only, gone on disconnect) or a
 * streamer (kept in {@code LiveViewerClientConfig}, so that player stays silent on every stream they
 * run, on either slot).
 *
 * <p>A stream is known by its playlist URL (a new broadcast is a new URL; a takeover too). A streamer
 * is known by name, and by uuid once one is known: the relay's status names the streamer, and their
 * own Live chat lines carry their uuid ({@link #learn}), so a muted player who renames stays muted.
 * The rules are pure statics so the tests reach them without a client.</p>
 */
public final class LiveViewerMutes {

    /** The most streamers a viewer can keep muted; the oldest goes first past it. */
    static final int MAX_MUTED = 200;

    /** One muted streamer: {@code uuid} 32 lower-case hex, or null while unknown. */
    public record Muted(@Nullable String uuid, String name) {

        /** Config form: {@code uuid|name}, the uuid empty while unknown. */
        public String serialize() {
            return (uuid == null ? "" : uuid) + "|" + name;
        }

        /** A config entry back, or null when it is not one. */
        @Nullable
        public static Muted parse(@Nullable String entry) {
            if (entry == null) return null;
            int bar = entry.indexOf('|');
            if (bar < 0) return null;
            String name = entry.substring(bar + 1).trim();
            if (!validName(name)) return null;
            return new Muted(normUuid(entry.substring(0, bar)), name);
        }

        /** Whether this entry is the streamer {@code name} / {@code uuid}: uuid when both are known, else name. */
        public boolean is(@Nullable String name, @Nullable String uuid) {
            String u = normUuid(uuid);
            if (this.uuid != null && u != null) return this.uuid.equals(u);
            return name != null && this.name.equalsIgnoreCase(name.trim());
        }
    }

    /** Streams muted this session, by playlist URL. */
    private static final Set<String> STREAMS = ConcurrentHashMap.newKeySet();
    /** Streamer name (lower case) → uuid, from their own Live chat lines. */
    private static final Map<String, String> UUIDS = new ConcurrentHashMap<>();

    private LiveViewerMutes() {}

    /** A Minecraft-style player name: 1 to 16 letters, digits or underscores. */
    static boolean validName(@Nullable String name) {
        return name != null && name.matches("[A-Za-z0-9_]{1,16}");
    }

    /** {@code uuid} as 32 lower-case hex (dashes dropped), or null when it is not a uuid. */
    @Nullable
    static String normUuid(@Nullable String uuid) {
        if (uuid == null) return null;
        String u = uuid.replace("-", "").trim().toLowerCase(Locale.ROOT);
        return u.matches("[0-9a-f]{32}") ? u : null;
    }

    /** Whether any entry of {@code muted} is this streamer. */
    public static boolean streamerMuted(List<Muted> muted, @Nullable String name, @Nullable String uuid) {
        if (name == null && uuid == null) return false;
        for (Muted m : muted) {
            if (m.is(name, uuid)) return true;
        }
        return false;
    }

    /**
     * Whether a stream may be heard: TV stream sound on, the stream not muted, its streamer not muted.
     * Pure, for the tests.
     */
    static boolean audible(boolean soundOn, Set<String> mutedStreams, List<Muted> mutedStreamers,
                           @Nullable String streamKey, @Nullable String name, @Nullable String uuid) {
        if (!soundOn) return false;
        if (streamKey != null && mutedStreams.contains(streamKey)) return false;
        return !streamerMuted(mutedStreamers, name, uuid);
    }

    /**
     * {@code muted} with this streamer added (an existing entry for them refreshed with the newer name and
     * any uuid now known), newest last, at most {@link #MAX_MUTED}. A new list; {@code muted} is untouched.
     */
    static List<Muted> with(List<Muted> muted, String name, @Nullable String uuid) {
        String u = normUuid(uuid);
        List<Muted> out = new ArrayList<>(muted.size() + 1);
        String keptUuid = u;
        for (Muted m : muted) {
            if (m.is(name, u)) {
                if (keptUuid == null) keptUuid = m.uuid();
                continue;
            }
            out.add(m);
        }
        out.add(new Muted(keptUuid, name.trim()));
        while (out.size() > MAX_MUTED) out.remove(0);
        return List.copyOf(out);
    }

    /** {@code muted} without this streamer. A new list; {@code muted} is untouched. */
    static List<Muted> without(List<Muted> muted, @Nullable String name, @Nullable String uuid) {
        List<Muted> out = new ArrayList<>(muted.size());
        for (Muted m : muted) {
            if (!m.is(name, uuid)) out.add(m);
        }
        return List.copyOf(out);
    }

    // ---- this client's state ---------------------------------------------------------------------

    /** The uuid learnt for {@code name}, or null. */
    @Nullable
    public static String uuidOf(@Nullable String name) {
        return name == null ? null : UUIDS.get(name.toLowerCase(Locale.ROOT));
    }

    /** A Live chat line from the game: {@code name} is the player whose uuid is {@code uuid}. */
    public static void learn(@Nullable String name, @Nullable String uuid) {
        String u = normUuid(uuid);
        if (!validName(name) || u == null) return;
        UUIDS.put(name.toLowerCase(Locale.ROOT), u);
    }

    /** Whether the stream at {@code streamKey} by {@code name} may be heard now. */
    public static boolean audible(@Nullable String streamKey, @Nullable String name) {
        return audible(LiveViewerClientConfig.tvStreamSound(), STREAMS,
            LiveViewerClientConfig.mutedStreamers(), streamKey, name, uuidOf(name));
    }

    public static void muteStream(String streamKey) {
        STREAMS.add(streamKey);
    }

    public static void unmuteStream(String streamKey) {
        STREAMS.remove(streamKey);
    }

    public static boolean streamMuted(@Nullable String streamKey) {
        return streamKey != null && STREAMS.contains(streamKey);
    }

    /** Mute {@code name} for good (kept in the config). */
    public static void muteStreamer(String name) {
        LiveViewerClientConfig.setMutedStreamers(
            with(LiveViewerClientConfig.mutedStreamers(), name, uuidOf(name)));
    }

    /** Unmute {@code entry}'s streamer. */
    public static void unmuteStreamer(Muted entry) {
        LiveViewerClientConfig.setMutedStreamers(
            without(LiveViewerClientConfig.mutedStreamers(), entry.name(), entry.uuid()));
    }

    /** Disconnect: stream mutes last one session. */
    public static void clearSession() {
        STREAMS.clear();
    }
}
