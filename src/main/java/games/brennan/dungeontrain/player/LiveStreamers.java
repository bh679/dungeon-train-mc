package games.brennan.dungeontrain.player;

import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Which players on this server currently hold a Live Feed stream — server-side, in memory only.
 *
 * <p>Nothing persists: a server restart ends the stream anyway (the client's ffmpeg died with it)
 * and the relay's TTL forgets the channel within a minute. The relay, not this, decides who is
 * live across worlds; this only remembers who to tell when someone else on the same server takes
 * over, and who to stop on death or logout.</p>
 *
 * <p>There can be more than one: while the Two Streams reward is on, the relay hands a second
 * player a second channel instead of the takeover prompt, and both may well be on the same server.
 * Streamers are kept in claim order, so {@link #any} is the longest-running one.</p>
 *
 * <p>A player who has put a Live Eye on but is not live yet — their client is still claiming, or
 * someone else holds the channel and they are deciding whether to take it over — is <em>pending</em>.
 * They become a streamer only when their client says the relay gave them a channel
 * ({@code net/LiveClaimResultPacket}).</p>
 */
public final class LiveStreamers {

    private static final Map<MinecraftServer, Set<UUID>> STREAMERS = new WeakHashMap<>();
    /** Each streamer's profile name, so the relay's {@code prevName} can name who it cut off. */
    private static final Map<MinecraftServer, Map<UUID, String>> NAMES = new WeakHashMap<>();
    /** Pending wearers → whether they put the Live Eye on in place of a TV while someone else was live. */
    private static final Map<MinecraftServer, Map<UUID, Boolean>> PENDING = new WeakHashMap<>();
    /** When each streamer started (wall clock), so {@code ServerLiveStatus} knows if the relay answered since. */
    private static final Map<MinecraftServer, Map<UUID, Long>> STARTED = new WeakHashMap<>();

    private LiveStreamers() {}

    /** {@code player} holds a stream from now. True when they were not already a streamer. */
    public static synchronized boolean add(MinecraftServer server, UUID player) {
        return add(server, player, "");
    }

    /** {@link #add(MinecraftServer, UUID)}, remembering the streamer's profile {@code name}. */
    public static synchronized boolean add(MinecraftServer server, UUID player, String name) {
        NAMES.computeIfAbsent(server, s -> new HashMap<>()).put(player, name == null ? "" : name);
        boolean added = STREAMERS.computeIfAbsent(server, s -> new LinkedHashSet<>()).add(player);
        if (added) STARTED.computeIfAbsent(server, s -> new HashMap<>()).put(player, System.currentTimeMillis());
        return added;
    }

    /**
     * When the second of this server's streamers started: from then on two streams run here. 0 while
     * fewer than two are streaming.
     */
    public static synchronized long bothStartedMs(MinecraftServer server) {
        Map<UUID, Long> started = STARTED.get(server);
        return started == null ? 0L : secondEarliest(started.values());
    }

    /** The second-smallest start time, or 0 with fewer than two. Pure, for the tests. */
    static long secondEarliest(java.util.Collection<Long> starts) {
        if (starts.size() < 2) return 0L;
        return starts.stream().sorted().skip(1).findFirst().orElse(0L);
    }

    /** The other streamers on this server with their names (empty when unknown), in claim order. */
    public static synchronized Map<UUID, String> othersWithNames(MinecraftServer server, UUID player) {
        Map<UUID, String> names = NAMES.getOrDefault(server, Map.of());
        Map<UUID, String> out = new java.util.LinkedHashMap<>();
        for (UUID id : othersThan(server, player)) out.put(id, names.getOrDefault(id, ""));
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * The other streamers on this server — everyone but {@code player}. The relay's takeover cut
     * exactly one of them off; the caller decides what to do with them.
     */
    public static synchronized Set<UUID> othersThan(MinecraftServer server, UUID player) {
        Set<UUID> all = STREAMERS.get(server);
        if (all == null) return Set.of();
        Set<UUID> out = new LinkedHashSet<>(all);
        out.remove(player);
        return Set.copyOf(out);
    }

    /** Whether {@code player} is streaming on this server right now. */
    public static synchronized boolean isStreamer(MinecraftServer server, @Nullable UUID player) {
        Set<UUID> all = STREAMERS.get(server);
        return player != null && all != null && all.contains(player);
    }

    /** The longest-running streamer on this server, or null if nobody is streaming. */
    @Nullable
    public static synchronized UUID any(MinecraftServer server) {
        Set<UUID> all = STREAMERS.get(server);
        return all == null || all.isEmpty() ? null : all.iterator().next();
    }

    /** Everyone streaming on this server, in claim order; empty when nobody is. */
    public static synchronized Set<UUID> all(MinecraftServer server) {
        Set<UUID> all = STREAMERS.get(server);
        return all == null ? Set.of() : Set.copyOf(all);
    }

    /** {@code player} wears a Live Eye and is not live yet; {@code fromWatching} as {@link #takePending}. */
    public static synchronized void addPending(MinecraftServer server, UUID player, boolean fromWatching) {
        PENDING.computeIfAbsent(server, s -> new HashMap<>()).put(player, fromWatching);
    }

    public static synchronized boolean isPending(MinecraftServer server, UUID player) {
        Map<UUID, Boolean> m = PENDING.get(server);
        return m != null && m.containsKey(player);
    }

    /**
     * Forget {@code player} as pending. Null when they were not; else whether they swapped a TV for
     * the Live Eye while someone else was live (the "took over from a TV" advancement).
     */
    @Nullable
    public static synchronized Boolean takePending(MinecraftServer server, UUID player) {
        Map<UUID, Boolean> m = PENDING.get(server);
        return m == null ? null : m.remove(player);
    }

    /** Forget {@code player} if they are a streamer; true when they were. */
    public static synchronized boolean clearIf(MinecraftServer server, UUID player) {
        Set<UUID> all = STREAMERS.get(server);
        if (all == null || !all.remove(player)) return false;
        Map<UUID, String> names = NAMES.get(server);
        if (names != null) names.remove(player);
        Map<UUID, Long> started = STARTED.get(server);
        if (started != null) started.remove(player);
        if (all.isEmpty()) STREAMERS.remove(server);
        return true;
    }
}
