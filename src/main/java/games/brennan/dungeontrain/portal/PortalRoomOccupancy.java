package games.brennan.dungeontrain.portal;

import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Whether a player still counts as <b>in</b> a dimensional room — the question that decides whether
 * the room is drained, its chunks released and its train let go.
 *
 * <h2>Why not just the structure's box</h2>
 * <p>The structure's box stops one row over the room's roof, and a player can be on the far side of
 * that roof without having left: they mined through the ceiling and climbed out onto it, or broke
 * the floor and dropped into the space beneath. Asked with the plain box, such a player read as
 * nobody — so the room drained its copies out from under them, its chunks were released, and they
 * fell out of the world (M1lfHunter2xG on v0.1781.0: an endless room, ceiling broken, roof climbed,
 * dead in the void eleven seconds later). Worse, the box itself grows and shrinks with the corridor
 * copies standing in it, so a player just over the roof flickered in and out every few ticks, the
 * copy under them laid and retired each time.</p>
 *
 * <h2>A margin, held in by two guards</h2>
 * <p>The box is grown by {@link #ROOF_MARGIN} over the roof and {@link #UNDER_FLOOR_MARGIN} under the
 * floor — enough to stand on the roof, jump and pillar a little; not the whole lane. A position in
 * that margin counts only if:</p>
 * <ul>
 *   <li><b>it is in twin space</b> — under the bedrock, or in the attic where a lid stands over it.
 *       Rooms are never built anywhere a train runs, so a player back on the deck can never be
 *       counted, whatever the margin;</li>
 *   <li><b>it is in no other room's own box.</b> Rooms are stacked in lanes, and an eight-row
 *       headroom once read a player walking the upper room as standing in the lower one, whose rescue
 *       then carried them out of a room that was working (2026-10-21, pair -105 over -138). A player
 *       inside another room is that room's, full stop.</li>
 * </ul>
 *
 * <p>Pure apart from {@link AABB}, so it unit-tests without a NeoForge bootstrap.</p>
 */
public final class PortalRoomOccupancy {

    /** Rows over a structure's own box in which a player still counts as inside it. */
    public static final int ROOF_MARGIN = 8;

    /** Rows under a structure's own box in which a player still counts as inside it. */
    public static final int UNDER_FLOOR_MARGIN = 4;

    private PortalRoomOccupancy() {}

    /** {@code own} grown by the two margins, vertically only. */
    public static AABB grown(AABB own) {
        return new AABB(own.minX, own.minY - UNDER_FLOOR_MARGIN, own.minZ,
            own.maxX, own.maxY + ROOF_MARGIN, own.maxZ);
    }

    /**
     * Whether a player at {@code (x, y, z)} counts as inside the structure whose box is {@code own}.
     *
     * @param inTwinSpace whether {@code (x, y)} is in twin space — the margin counts nowhere else
     * @param others      every other standing structure's own box
     */
    public static boolean counts(double x, double y, double z, AABB own, boolean inTwinSpace,
                                 List<AABB> others) {
        if (own.contains(x, y, z)) return true;
        if (!inTwinSpace || !grown(own).contains(x, y, z)) return false;
        for (AABB other : others) {
            if (other.contains(x, y, z)) return false;
        }
        return true;
    }
}
