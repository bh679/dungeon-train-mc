package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.net.DeathStatsPacket;
import org.jetbrains.annotations.Nullable;

/**
 * Client-only holder for the most recent {@link DeathStatsPacket} received
 * from the server. Populated by the packet handler on every player death and
 * read by {@code DeathScreenLayoutHandler}'s render hook.
 *
 * <p>No expiry — overwritten on every new death packet, which is sufficient
 * since each death triggers exactly one send. Reset to {@code null} only on
 * JVM lifetime (process restart) so a player rejoining a session keeps the
 * panel visible until their next death.</p>
 */
public final class DeathStatsCache {

    private static volatile @Nullable DeathStatsPacket last;

    private DeathStatsCache() {}

    public static void set(DeathStatsPacket packet) {
        last = packet;
    }

    public static @Nullable DeathStatsPacket get() {
        return last;
    }
}
