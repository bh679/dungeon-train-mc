package games.brennan.dungeontrain.narrative;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Who is due a leaderboard rank refetch, and when" — a due-tick per player, drained by the server
 * tick handler in {@code LeaderboardRefreshEvents}.
 *
 * <p>A death cannot refetch ranks on the spot: the death's own telemetry only leaves in the trailing
 * flush of {@code RunStatsEvents}' {@code RelayOutbox.runBatched(...)} block, so an immediate
 * {@code /leaderboard/me} would race it and read back the <em>pre</em>-death position — precisely the
 * staleness the refetch exists to remove. So the death schedules, and a later tick fetches.</p>
 *
 * <p>Free of Minecraft types on purpose: the scheduling rule is the part worth testing, and it tests
 * without a server. Ticks are passed in rather than read, for the same reason.</p>
 */
public final class LeaderboardRankSchedule {

    /** Player → the server tick at which their refetch comes due. */
    private final Map<UUID, Long> due = new ConcurrentHashMap<>();

    /**
     * Note that {@code player} should be refetched at {@code dueTick}.
     *
     * <p>An already-scheduled player keeps the EARLIER tick. Two deaths in quick succession should
     * settle at one fetch that happens promptly, not one that keeps being pushed further out by each
     * new death — a player dying repeatedly is exactly the player whose rank is moving.</p>
     */
    public void schedule(UUID player, long dueTick) {
        if (player == null) return;
        due.merge(player, dueTick, Math::min);
    }

    /** Everyone due at or before {@code nowTick}, removed as they are returned. Never null. */
    public List<UUID> drainDue(long nowTick) {
        if (due.isEmpty()) return List.of();
        List<UUID> out = new ArrayList<>();
        for (Iterator<Map.Entry<UUID, Long>> it = due.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            if (e.getValue() <= nowTick) {
                out.add(e.getKey());
                it.remove();
            }
        }
        return out;
    }

    /** Drop any pending refetch for {@code player} — call on logout; nobody is left to read a book. */
    public void cancel(UUID player) {
        if (player != null) due.remove(player);
    }

    /** True when nothing is pending, so the tick handler can return without allocating. */
    public boolean isEmpty() {
        return due.isEmpty();
    }

    /** Test seam — forget every pending refetch. */
    public void clear() {
        due.clear();
    }
}
