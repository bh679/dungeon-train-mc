package games.brennan.dungeontrain.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a player was before {@code /dungeontrain portal test} put them inside a stamped dimensional
 * carriage, and what it stamped for them — the two halves of being able to undo it.
 *
 * <p>Modelled on {@link games.brennan.dungeontrain.editor.PortalRoomEditor}'s own session: same
 * fields, same job, and the same reason for existing — a teleport nobody can walk back from is a
 * trap, and a room stamped under the world with no record of it is litter.</p>
 *
 * <p><b>Session-only.</b> Nothing is persisted. A structure that outlives its session (a crash
 * between stamping and Back) is left standing in sealed basement space where nothing generates and
 * no player can dig; running the button again re-stamps over it.</p>
 */
public final class PortalTestSession {

    /**
     * One player's trip into a test carriage.
     *
     * @param structure what was stamped, so Back can sweep exactly that box
     * @param roomName  the room it was stamped from, for the message on the way back
     */
    public record Session(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch,
                          GameType previousGameType, PortalStructure structure, String roomName,
                          BlockPos arrival) {}

    /**
     * Pair key every test stamp is filed under.
     *
     * <p><b>It has to be a legal carriage index, not a sentinel.</b> The first attempt used
     * {@code Integer.MIN_VALUE / 2}, on the reasoning that a value that extreme could never collide
     * with a real group anchor. It does not collide, but the key does not stay a key: it is handed
     * down to the contents roller and the variant placer, which read it as a <i>position on the
     * track</i> and feed it to the difficulty frame. That logged two "cannot be a position on the
     * track" errors per stamp and fell back to tier 0 anyway.</p>
     *
     * <p>So it is tier 0 deliberately instead — the origin, a real index, which rolls the same
     * contents the fallback was already producing and says nothing to the log. It is also what
     * makes a copy's {@code variantIndexFor(tile, pairKey)} meaningful rather than arbitrary.</p>
     */
    public static final int PAIR_KEY = 0;

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private PortalTestSession() {}

    public static void put(UUID player, Session session) {
        SESSIONS.put(player, session);
    }

    /**
     * Replace the structure on a live session, leaving the return half alone.
     *
     * <p>The tiler is a fold — it returns a new structure carrying one more copy each tick — so the
     * session has to keep the latest one or Back would sweep the box the room had when it was first
     * stamped and leave the window standing.</p>
     */
    public static void updateStructure(UUID player, PortalStructure structure) {
        SESSIONS.computeIfPresent(player, (id, session) -> new Session(
            session.dimension(), session.pos(), session.yaw(), session.pitch(),
            session.previousGameType(), structure, session.roomName(), session.arrival()));
    }

    /** Every live trip, for the ticker. */
    public static java.util.Set<Map.Entry<UUID, Session>> entries() {
        return SESSIONS.entrySet();
    }

    public static Session get(UUID player) {
        return SESSIONS.get(player);
    }

    /** Take the session, so a second Back on the same trip finds nothing to do. */
    public static Session take(UUID player) {
        return SESSIONS.remove(player);
    }

    public static boolean has(UUID player) {
        return SESSIONS.containsKey(player);
    }

    public static void clear() {
        SESSIONS.clear();
    }
}
