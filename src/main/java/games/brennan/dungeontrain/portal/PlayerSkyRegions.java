package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PortalRoomSkyPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What each player was last told to light, for every sender of {@link PortalRoomSkyPacket}.
 *
 * <p><b>One memory because there is one slot.</b> The client holds a single lit region
 * ({@code ClientPortalRoomSky}), and three things describe one: a live pair's room, a
 * {@code /dt portal test} session, and a room standing on its editor plot. Each of those used to
 * keep its own "already sent" map, which is sound only while no two of them ever speak to the same
 * player — and they do. Walking from a plot into a test crosses the build-area gate, so the plot
 * sender takes its light back with a {@code none()} the moment the test's has gone out; the test's
 * own map still read "already sent", so it never sent again and the room tested dark.</p>
 *
 * <p>Sharing the memory makes that impossible rather than unlikely: whoever clears it clears it for
 * everyone, and the next sender to look sees a player who has been told nothing and tells them
 * again. Order between the senders stops mattering, which is the point — it was never something
 * their callers could see.</p>
 */
public final class PlayerSkyRegions {

    private static final Map<UUID, PortalRoomSkyPacket> LAST = new HashMap<>();

    private PlayerSkyRegions() {}

    /** Tell {@code player} to light {@code region}, unless that is what they were last told. */
    public static void send(ServerPlayer player, PortalRoomSkyPacket region) {
        if (player == null || region == null) return;
        UUID id = player.getUUID();
        if (region.equals(LAST.get(id))) return;
        LAST.put(id, region);
        DungeonTrainNet.sendTo(player, region);
    }

    /** Take the light back, if they have any. Answers whether anything was sent. */
    public static boolean clear(ServerPlayer player) {
        if (player == null || LAST.remove(player.getUUID()) == null) return false;
        DungeonTrainNet.sendTo(player, PortalRoomSkyPacket.none());
        return true;
    }

    /** Whether this player is currently lit by anything. */
    public static boolean holds(UUID id) {
        return LAST.containsKey(id);
    }

    /** True when nobody is lit — the early-out a per-tick sweep takes. */
    public static boolean isEmpty() {
        return LAST.isEmpty();
    }

    /** Forget a player without sending anything — they have gone. */
    public static void forget(UUID id) {
        LAST.remove(id);
    }

    /** Drop every entry: the server has stopped, and nothing is owed to anyone. */
    public static void clearAll() {
        LAST.clear();
    }
}
