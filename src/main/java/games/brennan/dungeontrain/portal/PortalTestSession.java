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

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private PortalTestSession() {}

    public static void put(UUID player, Session session) {
        SESSIONS.put(player, session);
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
