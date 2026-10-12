package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.ship.ManagedShip;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether Sable may wake a held carriage group on its own.
 *
 * <p><b>Why this exists.</b> Sable loads a held sub-level back the moment every chunk under its
 * stored bounds is loaded enough ({@code SubLevelHoldingChunk.canLoadSubLevel}). A train group
 * held just ahead of a force-loaded group meets that every 16 blocks: the pinned group's physics
 * chunk tickets travel forward with the train into the chunk the held one was filed in. The woken
 * group re-anchors to its real pose, which is out past those tickets, so Sable culls it on the
 * next container tick and files it again — a reload, a re-anchor and a serialise every chunk, for
 * a group nobody asked for (the {@code [reanchor] gapTicks=160} loop, {@code filed=} climbing).
 * Dungeon Train already brings back every held group it needs, explicitly, through
 * {@code SableShipyard.reloadFromHolding} (the players' window, the backward frontier, the
 * forward edge, remote players, portals), and that path does not go through
 * {@code canLoadSubLevel}. So while a player is near a train, a group of it outside the window the
 * appender holds is left held instead.</p>
 *
 * <p><b>When it stands aside.</b> Only a fresh window counts ({@link #FRESH_TICKS}): with no
 * player near, at world load, during a resume hold, or for anything that is not a registered
 * train group, Sable decides exactly as before. That keeps the cases that depend on Sable's own
 * resurrection — a train seen for the first time this session above all — unchanged.</p>
 *
 * <p>Fed once per tick per train by {@code TrainCarriageAppender.maintainTrailingForceLoadWindow};
 * read by {@code SubLevelHoldingChunkAutoLoadVetoMixin}. Server thread only.</p>
 */
public final class HeldGroupWakeVeto {

    /** A window older than this (game ticks) no longer vetoes anything. */
    static final long FRESH_TICKS = 20L;

    /**
     * The carriage range the appender holds resident for one train, and when it said so.
     * {@code [nearMinPIdx, holdTopPIdx]} is the near-player window including its front slack.
     */
    record Window(int nearMinPIdx, int holdTopPIdx, int groupSize, long tick) {}

    private static final Map<UUID, Window> WINDOWS = new ConcurrentHashMap<>();

    private HeldGroupWakeVeto() {}

    /** The appender holds {@code [nearMinPIdx, holdTopPIdx]} of {@code trainId} this tick. */
    static void record(UUID trainId, int nearMinPIdx, int holdTopPIdx, int groupSize, long tick) {
        WINDOWS.put(trainId, new Window(nearMinPIdx, holdTopPIdx, groupSize, tick));
    }

    /** The appender holds no window for {@code trainId} this tick: Sable decides. */
    static void forget(UUID trainId) {
        WINDOWS.remove(trainId);
    }

    /** Drop every window. Session boundary. */
    static void clear() {
        WINDOWS.clear();
    }

    /**
     * Whether Sable should leave {@code subLevelId} in holding rather than wake it this tick.
     * False for anything that is not a group of a train with a fresh window.
     */
    public static boolean shouldLeaveHeld(UUID subLevelId, long now) {
        if (subLevelId == null || WINDOWS.isEmpty()) return false;
        for (Map.Entry<UUID, Window> w : WINDOWS.entrySet()) {
            for (Map.Entry<Integer, ManagedShip> g : Trains.knownGroups(w.getKey()).entrySet()) {
                ManagedShip ship = g.getValue();
                if (ship == null || !subLevelId.equals(ship.subLevelId())) continue;
                return leaveHeld(w.getValue(), now, g.getKey());
            }
        }
        return false;
    }

    /**
     * Pure core: leave the group anchored at {@code anchorPIdx} held iff the window is fresh and
     * the group's carriages {@code [anchor, anchor + groupSize − 1]} lie wholly outside it.
     * Package-private for unit tests.
     */
    static boolean leaveHeld(Window w, long now, int anchorPIdx) {
        if (w == null || now - w.tick() > FRESH_TICKS || now < w.tick()) return false;
        if (w.nearMinPIdx() > w.holdTopPIdx()) return false;
        int highest = anchorPIdx + Math.max(1, w.groupSize()) - 1;
        return highest < w.nearMinPIdx() || anchorPIdx > w.holdTopPIdx();
    }
}
