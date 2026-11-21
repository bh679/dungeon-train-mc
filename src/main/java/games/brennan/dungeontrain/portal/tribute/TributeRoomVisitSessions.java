package games.brennan.dungeontrain.portal.tribute;

import games.brennan.dungeontrain.portal.PortalStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every death visit in progress, by visitor.
 *
 * <p>Server memory only: a visit does not survive a restart (the room on disk does), and the
 * structure it stood is swept when the visit ends ({@link TributeRoomVisitService}).</p>
 */
public final class TributeRoomVisitSessions {

    /**
     * One visit.
     *
     * @param slot         the site slot the structure stands in ({@link TributeRoomVisitSite})
     * @param deathDim     where they died, for the train keep-alive
     * @param deathPos     where they died
     * @param structure    what was stamped
     * @param window       the box to sweep when the visit ends (structure and corridors)
     * @param room         the room box itself, what is captured on save
     * @param screen       the TV's blocks
     * @param pose         where they were stood
     * @param blob         the room as loaded, for the save to build on
     * @param trainId      the train they died on, when known; it holds still while nobody watches it
     * @param kitGiven     whether a copy of their drops was handed in, and so is cleared on the way out
     * @param previousMode their game mode before the visit, restored on the way out
     * @param startedTick  when the visit began
     */
    public record Session(int slot, ResourceKey<Level> deathDim, Vec3 deathPos, PortalStructure structure,
                          BoundingBox window, BoundingBox room, List<BlockPos> screen,
                          TributeRoomCamera.Pose pose, TributeRoomBlob blob, @Nullable UUID trainId,
                          boolean kitGiven, GameType previousMode, long startedTick) {}

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private TributeRoomVisitSessions() {}

    public static void put(UUID player, Session session) {
        SESSIONS.put(player, session);
    }

    @Nullable
    public static Session get(UUID player) {
        return SESSIONS.get(player);
    }

    @Nullable
    public static Session take(UUID player) {
        return SESSIONS.remove(player);
    }

    public static boolean isVisiting(UUID player) {
        return SESSIONS.containsKey(player);
    }

    public static boolean isEmpty() {
        return SESSIONS.isEmpty();
    }

    public static Set<Map.Entry<UUID, Session>> entries() {
        return SESSIONS.entrySet();
    }

    public static Collection<Session> all() {
        return SESSIONS.values();
    }

    /** The slots in use, for {@link TributeRoomVisitSite#freeSlot}. */
    public static Set<Integer> takenSlots() {
        Set<Integer> taken = new HashSet<>();
        for (Session s : SESSIONS.values()) taken.add(s.slot());
        return taken;
    }

    /** Whether {@code (x, y, z)} is inside any visit's structure — what the spawn and rescue guards ask. */
    public static boolean contains(double x, double y, double z) {
        if (SESSIONS.isEmpty()) return false;
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        for (Session s : SESSIONS.values()) {
            if (s.window().isInside(bx, by, bz)) return true;
        }
        return false;
    }

    /** Whether some visitor died on {@code trainId}, so it should hold still while nobody else watches it. */
    public static boolean holdsTrain(UUID trainId) {
        if (SESSIONS.isEmpty()) return false;
        for (Session s : SESSIONS.values()) {
            if (trainId.equals(s.trainId())) return true;
        }
        return false;
    }

    /** Server stopped: nothing stands any more. */
    public static void clear() {
        SESSIONS.clear();
    }
}
