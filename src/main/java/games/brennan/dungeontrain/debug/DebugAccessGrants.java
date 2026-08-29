package games.brennan.dungeontrain.debug;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Who may open the F3+4 debug panel, and until when. The panel is a support tool the dev hands to
 * one player for a bounded window, so this store starts empty and everything is denied — there is
 * no singleplayer exemption and no operator exemption.
 *
 * <p>The relay is the source of truth for grants; this is the server's cache of what it last read
 * (see {@code net/relay/DebugGrantClient}). Caching is the point rather than an optimisation: a
 * relay outage must not silently revoke a live grant, so once a grant is known it stands on its own
 * expiry, through relay downtime and server restarts alike. That is also why it is persisted into
 * the world's saved data — see {@code DungeonTrainWorldData}.</p>
 *
 * <p>Expiry is evaluated against the wall clock on every read, so a lapsed grant is dead the moment
 * it lapses whether or not {@link #sweepExpired()} has run. The sweep only reclaims the map entry
 * and reports who changed, so the server can tell those clients to close the panel.</p>
 *
 * <p>Not thread-safe — owned by the world's saved data and touched from the server thread. The
 * relay client hops back onto the server thread before applying a fetch.</p>
 */
public final class DebugAccessGrants {

    private static final String TAG_UUID = "Uuid";
    private static final String TAG_EXPIRES_AT = "ExpiresAtMs";
    private static final String TAG_SOURCE = "Source";

    /** {@code expiresAtMs} value meaning "no expiry". */
    public static final long NEVER_EXPIRES = 0L;

    /**
     * The time blocks a grant can be issued for. The relay hands back an absolute
     * {@code expiresAtMs}, so these exist for the issuing surfaces and for tests — the mod never
     * has to reverse a duration out of an expiry.
     */
    public enum Duration {
        FIVE_MINUTES("5m", 5L * 60_000L),
        TWENTY_MINUTES("20m", 20L * 60_000L),
        ONE_HOUR("1h", 60L * 60_000L),
        ONE_DAY("1d", 24L * 60L * 60_000L),
        ONE_WEEK("1w", 7L * 24L * 60L * 60_000L),
        ONE_MONTH("1mo", 30L * 24L * 60L * 60_000L),
        FOREVER("forever", 0L);

        private final String token;
        private final long millis;

        Duration(String token, long millis) {
            this.token = token;
            this.millis = millis;
        }

        /** The canonical token an issuing surface uses ({@code 5m}, {@code 1h}, {@code forever}…). */
        public String token() {
            return token;
        }

        /** Absolute expiry for a grant issued at {@code nowMs}; {@link #NEVER_EXPIRES} for FOREVER. */
        public long expiryFrom(long nowMs) {
            return this == FOREVER ? NEVER_EXPIRES : nowMs + millis;
        }

        /** Parse an issuing surface's token, or null when it names no known block. */
        public static Duration fromToken(String raw) {
            if (raw == null) return null;
            String t = raw.trim().toLowerCase(Locale.ROOT);
            for (Duration d : values()) {
                if (d.token.equals(t)) return d;
            }
            return null;
        }
    }

    /** One cached grant. {@code source} is the issuing surface, kept for logging only. */
    public record Grant(long expiresAtMs, String source) {
        public Grant {
            source = source == null ? "" : source;
        }

        /** True while this grant is still live at {@code nowMs}. */
        public boolean liveAt(long nowMs) {
            return expiresAtMs == NEVER_EXPIRES || expiresAtMs > nowMs;
        }
    }

    private final Map<UUID, Grant> byPlayer = new LinkedHashMap<>();

    public boolean isEmpty() {
        return byPlayer.isEmpty();
    }

    /** Whether {@code player} may open the panel right now. */
    public boolean isGranted(UUID player) {
        Grant grant = byPlayer.get(player);
        return grant != null && grant.liveAt(System.currentTimeMillis());
    }

    /** The live grant for {@code player}, or null when there is none (or it has lapsed). */
    public Grant grantFor(UUID player) {
        Grant grant = byPlayer.get(player);
        return grant != null && grant.liveAt(System.currentTimeMillis()) ? grant : null;
    }

    /**
     * Record what the relay says about one player.
     *
     * @param grant the relay's grant, or null for "no grant" — which revokes any cached one
     * @return true when this changed the player's access, i.e. the client needs a re-sync
     */
    public boolean apply(UUID player, Grant grant) {
        Grant previous = byPlayer.get(player);
        if (grant == null) {
            return byPlayer.remove(player) != null;
        }
        byPlayer.put(player, grant);
        return previous == null
            || previous.expiresAtMs() != grant.expiresAtMs()
            || !previous.source().equals(grant.source());
    }

    /**
     * Drop every lapsed grant.
     *
     * @return the players whose grant just lapsed, so their clients can be told to close the panel
     */
    public List<UUID> sweepExpired() {
        long now = System.currentTimeMillis();
        List<UUID> lapsed = new ArrayList<>();
        byPlayer.entrySet().removeIf(e -> {
            if (e.getValue().liveAt(now)) return false;
            lapsed.add(e.getKey());
            return true;
        });
        return lapsed;
    }

    public ListTag toTag() {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Grant> e : byPlayer.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString(TAG_UUID, e.getKey().toString());
            entry.putLong(TAG_EXPIRES_AT, e.getValue().expiresAtMs());
            entry.putString(TAG_SOURCE, e.getValue().source());
            list.add(entry);
        }
        return list;
    }

    /**
     * Replace the contents from saved data. Lapsed and unparseable entries are dropped on the way
     * in — a save that sat on disk past a grant's expiry must not come back granting anything.
     */
    public void loadFrom(ListTag list) {
        byPlayer.clear();
        if (list == null) return;
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID uuid = parseUuid(entry.getString(TAG_UUID));
            if (uuid == null) continue;
            Grant grant = new Grant(entry.getLong(TAG_EXPIRES_AT), entry.getString(TAG_SOURCE));
            if (!grant.liveAt(now)) continue;
            byPlayer.put(uuid, grant);
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
