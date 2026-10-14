package games.brennan.dungeontrain.player;

import games.brennan.dungeontrain.registry.ModDataAttachments;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This life's seconds watching the Live Feed, for Faulthurst's stat book — the server-side twin of the
 * relay's one-life "Longest Viewer" board.
 *
 * <p>Only the client knows it rendered the feed, so the client reports each live poll's gap
 * ({@code net/LiveWatchedPacket}). A report is credited no more than the wall-clock time since the
 * player's previous one, and never more than {@link #MAX_REPORT_SECONDS}, so a modified client can
 * inflate its own book no faster than real time — the same bound the relay applies.</p>
 */
public final class LiveWatchTally {

    /** The relay's gap cap: a poll gap longer than this is a break. */
    public static final int MAX_REPORT_SECONDS = 30;

    private static final Map<UUID, Long> LAST_REPORT_MS = new ConcurrentHashMap<>();

    private LiveWatchTally() {}

    /** Credit {@code seconds} of watching to {@code player}'s current life, bounded as above. */
    public static void report(ServerPlayer player, int seconds, long nowMs) {
        long credited = credit(LAST_REPORT_MS.put(player.getUUID(), nowMs), seconds, nowMs);
        player.getData(ModDataAttachments.PLAYER_RUN_STATE.get()).addLiveWatchSeconds(credited);
    }

    /** Seconds a report earns: what it says, capped by the cap and by the time since the previous one. */
    static long credit(Long previousMs, int seconds, long nowMs) {
        long asked = Math.max(0, Math.min(seconds, MAX_REPORT_SECONDS));
        if (previousMs == null) return asked;
        long elapsed = Math.max(0, (nowMs - previousMs) / 1000);
        return Math.min(asked, elapsed);
    }

    /** Forget a player who left, so the map holds only the online. */
    public static void forget(UUID player) {
        LAST_REPORT_MS.remove(player);
    }
}
