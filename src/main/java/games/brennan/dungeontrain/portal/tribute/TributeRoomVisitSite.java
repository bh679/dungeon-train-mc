package games.brennan.dungeontrain.portal.tribute;

import net.minecraft.core.BlockPos;

import java.util.Set;

/**
 * Where a death visit stands its room: a private band of the basement, one slot per visitor.
 *
 * <p>Pure integer geometry so the slot rule can be unit-tested: the band is well off the track and
 * off the portal test rig's band ({@code PortalTestCommand.TEST_Z_OFFSET} is +512), and slots are
 * spaced further apart than a structure is long, so two players dead at once never share a wall.</p>
 */
public final class TributeRoomVisitSite {

    /** Z of every visit structure. Negative where the test rig's is positive; the track band is near 0. */
    public static final int Z_BAND = -512;
    /** X between slots. A structure's span is the room plus two corridors and plugs: well under this. */
    public static final int SLOT_STRIDE = 64;

    /**
     * Slots whose chunks Smaller saves always writes ({@link #isSiteChunk}): enough for every visitor of
     * a busy server at once. A slot past these still works; its chunks are simply generated again.
     */
    public static final int KEPT_SLOTS = 8;
    /** Blocks either side of {@link #Z_BAND} a visit's room, tubes and sweep window can reach. */
    static final int Z_REACH = 64;

    private TributeRoomVisitSite() {}

    /**
     * Whether chunk ({@code cx}, {@code cz}) is under one of the first {@link #KEPT_SLOTS} visit sites.
     * Smaller saves writes these even though no player changed them: a visit's stamp is not a player
     * change, and dropping a swept site meant generating its chunks again on every death.
     */
    public static boolean isSiteChunk(int cx, int cz) {
        int minCx = -Z_REACH >> 4;
        int maxCx = (KEPT_SLOTS * SLOT_STRIDE + Z_REACH) >> 4;
        int minCz = (Z_BAND - Z_REACH) >> 4;
        int maxCz = (Z_BAND + Z_REACH) >> 4;
        return cx >= minCx && cx <= maxCx && cz >= minCz && cz <= maxCz;
    }

    /** The lowest slot not in {@code taken}. */
    public static int freeSlot(Set<Integer> taken) {
        int slot = 0;
        while (taken.contains(slot)) slot++;
        return slot;
    }

    /**
     * The structure origin for {@code slot}: the corridor lane's floor lifted by the room's
     * door-height offset, exactly as the test rig stands a room on the lane floor (see
     * {@code PortalTestCommand.originFor} for why the offset is spent here).
     */
    public static BlockPos originFor(int slot, int laneFloorY, int doorHeightOffset) {
        return new BlockPos(slot * SLOT_STRIDE, laneFloorY + doorHeightOffset, Z_BAND);
    }
}
