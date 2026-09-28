package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.net.PortalPrewarmPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What each player has already been told to build, so the two portal systems can say it as often as
 * they like and it goes over the wire only when it is news.
 *
 * <p>Both callers are in the same position — they are walking a corridor every tick and know where
 * its other end is every one of them — and both would otherwise send the same destination twenty
 * times a second. Shared rather than duplicated because the rule for "news" is the interesting part
 * and is not obvious: it is the destination's <b>section</b>, which is the granularity the client
 * builds at, so a step down a corridor that lands in the same one is not worth a packet.</p>
 *
 * <p><b>And a clock beside it</b>, because a player standing still in a corridor would otherwise let
 * their hint lapse: the client expires a destination it stops hearing about, which is what stops a
 * stale one outliving the walk, so a still-current one has to be restated occasionally to survive
 * that. {@link #REFRESH_TICKS} is comfortably inside the client's own expiry.</p>
 */
public final class PortalPrewarmHints {

    /**
     * How long a hint stands before it is restated, in ticks.
     *
     * <p>Two seconds: far enough apart that a player standing in a corridor costs a packet now and
     * then rather than every tick, and well inside the ten seconds the client gives a destination
     * before dropping it.</p>
     */
    public static final int REFRESH_TICKS = 40;

    /** What one player was last told, and when. */
    private record Hint(long section, long sentAt) {}

    private static final Map<UUID, Hint> LAST = new HashMap<>();

    private PortalPrewarmHints() {}

    /** Tell this player where they are about to land, if that is not what they were last told. */
    public static void send(ServerPlayer player, BlockPos destination, long gameTime) {
        UUID id = player.getUUID();
        long section = SectionPos.asLong(destination);

        Hint last = LAST.get(id);
        if (last != null && last.section() == section && gameTime - last.sentAt() < REFRESH_TICKS) {
            return;
        }
        LAST.put(id, new Hint(section, gameTime));
        PacketDistributor.sendToPlayer(player, new PortalPrewarmPacket(destination));
    }

    /**
     * Forget what this player was told.
     *
     * <p>No "you have stopped" message goes with it — the client's own expiry is what ends a
     * prewarm, so leaving a corridor is silence rather than a packet. This only makes sure the next
     * corridor they walk into is news again.</p>
     */
    public static void forget(UUID player) {
        LAST.remove(player);
    }

    /**
     * Forget everybody who is no longer in the world.
     *
     * <p>A player who logs out mid-corridor is never told anything more, so without this their entry
     * would sit here for the rest of the session. The same housekeeping the corridor hold does, and
     * cheap for the same reason: it runs over a level's player list, which is small.</p>
     */
    public static void retain(Collection<ServerPlayer> players) {
        LAST.keySet().removeIf(id -> players.stream().noneMatch(p -> p.getUUID().equals(id)));
    }

    /**
     * Whether anybody is still remembered here.
     *
     * <p>What lets the dispatch skip a tick in which nothing is in a corridor <i>and</i> nothing is
     * left to forget — the same early-out the corridor hold takes, and for the same reason: portal
     * carriages tick for every train on the map, and most ticks have no portal player at all.</p>
     */
    public static boolean anyOutstanding() {
        return !LAST.isEmpty();
    }

    /** Drop everything. Wired to a world closing, like every other portal cache. */
    public static void clear() {
        LAST.clear();
    }
}
