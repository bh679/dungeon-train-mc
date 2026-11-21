package games.brennan.dungeontrain.event;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player roof-run streaks for {@link RoofRunEvents}, kept apart from the level scan so the rules
 * can be tested without a world.
 *
 * <p>A streak holds the lowest and highest group anchor reached in one uninterrupted run, the scan it
 * was last touched on, and the level it runs in. Continuity is "touched on the scan right before this
 * one, in the same level". The level is part of the test because game time is per world: a new world's
 * clock starts again from low numbers, so a streak left over from the previous world (same JVM, same
 * player UUID, after Board anew) could otherwise line up with it and pay out a run nobody made. Callers
 * also {@link #clear} on death, respawn, logout and dimension change, and {@link #clearAll} on server
 * stop; the level key is the second lock.</p>
 *
 * <p>Server-thread only.</p>
 */
final class RoofRunStreaks {

    /** What one scan found the player doing. */
    enum Kind { OFF, ON_TOP_OTHER, BELOW_ROOF, ON_ROOF }

    private final long scanPeriod;
    private final Map<UUID, Streak> streaks = new HashMap<>();

    RoofRunStreaks(long scanPeriod) {
        this.scanPeriod = scanPeriod;
    }

    /**
     * Record one scan for {@code player} and return the group count to fire the trigger with, if any.
     *
     * @param level     an identity for the level being scanned (its dimension key, with the server)
     * @param anchor    the group anchor pIdx; only read for {@link Kind#ON_ROOF}
     * @param groupSize carriages per group; only read for {@link Kind#ON_ROOF}
     */
    OptionalInt observe(UUID player, Object level, Kind kind, int anchor, int groupSize, long now) {
        Streak s = streaks.get(player);
        boolean continuous = s != null && s.lastScan == now - scanPeriod && Objects.equals(s.level, level);
        switch (kind) {
            case ON_ROOF -> {
                int gs = Math.max(1, groupSize);
                Streak next = continuous
                    ? new Streak(Math.min(s.min, anchor), Math.max(s.max, anchor), now, gs, level)
                    : new Streak(anchor, anchor, now, gs, level);
                streaks.put(player, next);
                int groups = (next.max - next.min) / gs;
                return groups > 0 ? OptionalInt.of(groups) : OptionalInt.empty();
            }
            case ON_TOP_OTHER -> {
                // On a pad deck or airborne over the top: keeps a live streak going across the
                // coupling, and counts the roof just crossed (+1), so one group roof then the pad
                // earns threshold 1. Never starts a streak.
                if (!continuous) {
                    if (s != null) streaks.remove(player);
                    return OptionalInt.empty();
                }
                streaks.put(player, new Streak(s.min, s.max, now, s.groupSize, level));
                return OptionalInt.of((s.max - s.min) / s.groupSize + 1);
            }
            default -> {
                streaks.remove(player);
                return OptionalInt.empty();
            }
        }
    }

    void clear(UUID player) {
        streaks.remove(player);
    }

    void clearAll() {
        streaks.clear();
    }

    /** Drop streaks of players no longer on the server. */
    void retainOnly(Set<UUID> online) {
        streaks.keySet().retainAll(online);
    }

    boolean has(UUID player) {
        return streaks.containsKey(player);
    }

    private record Streak(int min, int max, long lastScan, int groupSize, Object level) {}
}
