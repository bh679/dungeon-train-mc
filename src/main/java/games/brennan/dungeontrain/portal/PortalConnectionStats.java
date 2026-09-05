package games.brennan.dungeontrain.portal;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * How a player's dimensional carriages went this life: how many connected, how many broke, and why.
 *
 * <p><b>What it is for.</b> A portal that does not connect is invisible from outside a live run — the
 * player walks up to a wall, and nothing in a log says how often that happens across the player
 * base. This keeps a per-life tally that {@code PortalStatsReporter} sends to the relay once, at
 * death, so the ratio of connections to breakages per version is a number rather than a report.</p>
 *
 * <p><b>Costs nothing per tick.</b> The two counters are bumped at events that already happen once
 * — the swap that carries a player into the twin, and the moment the walk-through opens a plate or
 * a severed pair refuses them — and read once at death. Nothing here is polled.</p>
 *
 * <p><b>A breakage counts once per corridor per life.</b> Standing at one dead door for a minute is
 * one broken connection, not sixty; walking away and coming back to the same door is still the same
 * broken connection. Connections are not de-duplicated: going through the same portal twice is two
 * crossings that worked.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 */
public final class PortalConnectionStats {

    /** One life's tally, immutable. {@code reasons} maps a refusal reason name to its count. */
    public record Life(int connected, int broken, Map<String, Integer> reasons) {

        /** True when this life met no dimensional carriage at all — nothing worth reporting. */
        public boolean isEmpty() {
            return connected == 0 && broken == 0;
        }
    }

    private static final Life EMPTY = new Life(0, 0, Map.of());

    private static final class Tally {
        int connected;
        final Map<String, Integer> reasons = new TreeMap<>();
        final Set<Integer> brokenAt = new HashSet<>();
    }

    private static final Map<UUID, Tally> TALLIES = new HashMap<>();

    private PortalConnectionStats() {}

    /** A swap carried this player from a carriage corridor into its twin: the portal connected. */
    public static synchronized void noteConnected(UUID player) {
        TALLIES.computeIfAbsent(player, key -> new Tally()).connected++;
    }

    /**
     * This player met a corridor whose portal did not connect.
     *
     * @param carriageIndex the corridor, so a second refusal at the same door is not a second break
     * @param reason        the refusal reason's name, from {@code PortalSwapDiagnostics.Reason}
     * @return {@code true} if this was a new breakage for this life
     */
    public static synchronized boolean noteBroken(UUID player, int carriageIndex, String reason) {
        Tally tally = TALLIES.computeIfAbsent(player, key -> new Tally());
        if (!tally.brokenAt.add(carriageIndex)) return false;
        tally.reasons.merge(reason == null ? "UNKNOWN" : reason, 1, Integer::sum);
        return true;
    }

    /** This life's tally so far, without clearing it. */
    public static synchronized Life peek(UUID player) {
        Tally tally = TALLIES.get(player);
        return tally == null ? EMPTY : snapshot(tally);
    }

    /** This life's tally, and the start of the next: the life is over. */
    public static synchronized Life takeForLife(UUID player) {
        Tally tally = TALLIES.remove(player);
        return tally == null ? EMPTY : snapshot(tally);
    }

    /** Drop a player's tally without reporting it — they logged out. */
    public static synchronized void forget(UUID player) {
        TALLIES.remove(player);
    }

    /** Drop everything — the server stopped. */
    public static synchronized void clear() {
        TALLIES.clear();
    }

    private static Life snapshot(Tally tally) {
        return new Life(tally.connected, tally.brokenAt.size(),
            Collections.unmodifiableMap(new TreeMap<>(tally.reasons)));
    }
}
