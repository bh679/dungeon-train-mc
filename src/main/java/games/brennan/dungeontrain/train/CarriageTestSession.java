package games.brennan.dungeontrain.train;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is standing in a Test-the-Carriage copy of a <b>carriage or contents</b> template, and how to
 * send them back — {@code command.CarriageTestCommand}'s half of what
 * {@code portal.PortalTestSession} is for dimensional carriages.
 *
 * <p><b>Separate from the portal session on purpose.</b> {@code PortalTestSession.anyActive()} is
 * read by the portal ambience, the room tiler and skybox occlusion, none of which a carriage test
 * has anything to do with. Filing a carriage test there would switch all of them on.</p>
 */
public final class CarriageTestSession {

    /** What was tested: the template the author asked for, named by its editor category. */
    public enum Kind {
        CARRIAGE("carriages"),
        CONTENTS("contents");

        private final String literal;

        Kind(String literal) { this.literal = literal; }

        /** The command literal and editor category id — the same word in both places. */
        public String literal() { return literal; }
    }

    /**
     * One player's trip into a test carriage.
     *
     * @param templateId the carriage or contents id asked for — a group parent, not the member it rolled
     * @param box        everything that was stamped, so Back can sweep exactly that
     */
    public record Session(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch,
                          GameType previousGameType, Kind kind, String templateId,
                          BoundingBox box) {}

    /**
     * The carriage index every test copy is rolled at.
     *
     * <p>A legal index, not a sentinel, for the reason {@code PortalTestSession.PAIR_KEY} gives: the
     * index is read downstream as a position on the track, and {@code -1} is the editor sentinel that
     * turns every roll off — the opposite of what a test is for.</p>
     */
    public static final int TEST_INDEX = 0;

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private CarriageTestSession() {}

    public static void put(UUID player, Session session) { SESSIONS.put(player, session); }

    public static Session get(UUID player) { return SESSIONS.get(player); }

    /** Take the session, so a second Back on the same trip finds nothing to do. */
    public static Session take(UUID player) { return SESSIONS.remove(player); }

    public static boolean has(UUID player) { return SESSIONS.containsKey(player); }

    public static boolean anyActive() { return !SESSIONS.isEmpty(); }

    /**
     * Whether a stamp at {@code carriageIndex} is a test copy: the test index, while a test is
     * running. Both halves, because {@link #TEST_INDEX} is also a real index a train can stand a
     * carriage at — only while a session exists is it certainly a test's. The session is registered
     * before the copy is stamped, so the very first stamp already reads as one.
     */
    public static boolean isTestStamp(int carriageIndex) {
        return carriageIndex == TEST_INDEX && anyActive();
    }

    public static void clear() { SESSIONS.clear(); }
}
