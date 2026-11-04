package games.brennan.dungeontrain.portal.home;

import net.minecraft.core.BlockPos;

import java.util.Set;

/**
 * Where a death visit stands its room: a private band of the basement, one slot per visitor.
 *
 * <p>Pure integer geometry so the slot rule can be unit-tested: the band is well off the track and
 * off the portal test rig's band ({@code PortalTestCommand.TEST_Z_OFFSET} is +512), and slots are
 * spaced further apart than a structure is long, so two players dead at once never share a wall.</p>
 */
public final class HomeRoomVisitSite {

    /** Z of every visit structure. Negative where the test rig's is positive; the track band is near 0. */
    public static final int Z_BAND = -512;
    /** X between slots. A structure's span is the room plus two corridors and plugs: well under this. */
    public static final int SLOT_STRIDE = 64;

    private HomeRoomVisitSite() {}

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
