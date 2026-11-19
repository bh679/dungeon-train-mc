package games.brennan.dungeontrain.compat.photo.album;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many live albums each player may keep: two for any donation, 10 for every full A$10, as the relay
 * says ({@code albumLimit} on {@code GET /albums/mine}, asked at login). Until it answers, with no
 * relay at all, or in Free Play, a player has one album, as everyone did before.
 */
final class AlbumDonors {

    private static final Map<UUID, Integer> limits = new ConcurrentHashMap<>();

    private AlbumDonors() {}

    static void record(UUID player, int albumLimit) {
        if (albumLimit > 1) limits.put(player, albumLimit);
        else limits.remove(player);
    }

    /** How many albums this player may keep in this run: at least 1. */
    static int limit(ServerPlayer player) {
        if (PlayerAlbums.kindFor(player) != AlbumKind.LIVE) return 1;
        return limits.getOrDefault(player.getUUID(), 1);
    }

    static void clear() {
        limits.clear();
    }
}
