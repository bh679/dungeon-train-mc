package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.config.DungeonTrainConfig;
import net.minecraft.server.level.ServerLevel;

/**
 * Decides which carriages along the train belong to a portal, and in what part.
 *
 * <p><b>A portal is a whole carriage group</b> — entry corridor, one cart, exit corridor — rather
 * than two corridors picked independently and however far apart the spacing happened to put them:</p>
 *
 * <pre>
 *   slot:   0            1             2          3+
 *         ENTRY   →   middle   →     EXIT   →  ordinary…
 * </pre>
 *
 * <p><b>Why the cart between them is not an ordinary carriage.</b> It is unreachable, and always was.
 * Walking forward through the entry corridor, the swap fires before the far door; walking back toward
 * the exit corridor, its near half swaps you out before you get there. So whatever sits between an
 * entry and its exit is sealed for good. Under the old rule that was every carriage in the gap —
 * three of them at the default spacing — each one rolled a variant, took a parts overlay, contents,
 * loot and mobs, and was then walled off from every player forever. Now it is exactly one carriage,
 * and it comes from {@link PortalCarriageBuilder#middleVariant()} so it is deliberate dead space
 * someone authored rather than three accidents.</p>
 *
 * <p><b>Index-based rather than random</b>, so the answer is stable: a carriage index yields the same
 * verdict on every reload and for every player, which matters because a carriage's blocks are
 * re-stamped whenever the rolling window brings it back round. A random pick would let a corridor
 * turn into an ordinary carriage under a player standing in it.</p>
 *
 * <p>The default is deliberately frequent — this is a test harness. Shipping portals every few groups
 * is a gameplay decision that has not been made.</p>
 */
public final class PortalCarriageSelection {

    /** Carriages a portal occupies: entry, the cart between, exit. */
    public static final int PORTAL_GROUP_SPAN = 3;

    /** Slot of the entry corridor within its group. */
    public static final int SLOT_ENTRY = 0;
    /** Slot of the cart between the two corridors. */
    public static final int SLOT_MIDDLE = 1;
    /** Slot of the exit corridor within its group. */
    public static final int SLOT_EXIT = 2;

    /** Every other group holds a portal while testing. */
    public static final int DEFAULT_CARRIAGE_EVERY = 2;

    /** Value meaning "no group holds a portal". */
    public static final int CARRIAGE_EVERY_OFF = 0;

    private PortalCarriageSelection() {}

    /**
     * A carriage's slot within its group.
     *
     * <p>{@link Math#floorMod} because carriage indices go negative when the train extends backwards,
     * and {@code %} alone would mirror the slot order either side of the origin — putting the exit
     * where the entry belongs on half the track.</p>
     */
    public static int slotOf(int carriageIndex, int groupSize) {
        return Math.floorMod(carriageIndex, Math.max(1, groupSize));
    }

    /** The anchor index of the group a carriage belongs to — and the key its portal is stored under. */
    public static int groupAnchorOf(int carriageIndex, int groupSize) {
        return carriageIndex - slotOf(carriageIndex, groupSize);
    }

    /** True if this group holds a portal, given portals every {@code every} groups. */
    public static boolean isPortalGroup(int carriageIndex, int groupSize, int every) {
        if (every <= CARRIAGE_EVERY_OFF) return false;
        // A group too short to hold entry, cart and exit gets no portal at all rather than half of
        // one — an entry corridor whose exit landed in the next group would strand anyone using it.
        if (groupSize < PORTAL_GROUP_SPAN) return false;

        long groupIndex = Math.floorDiv((long) carriageIndex, Math.max(1, groupSize));
        return Math.floorMod(groupIndex, (long) every) == 0L;
    }

    /** True if this carriage is one of a portal's two corridors. */
    public static boolean isPortalCarriage(ServerLevel level, int carriageIndex) {
        return isPortalCarriage(carriageIndex, DungeonTrainConfig.getGroupSize(), every(level));
    }

    /** True if this carriage is the cart between a portal's two corridors. */
    public static boolean isPortalMiddle(ServerLevel level, int carriageIndex) {
        return isPortalMiddle(carriageIndex, DungeonTrainConfig.getGroupSize(), every(level));
    }

    /** True if this carriage is any part of a portal — either corridor, or the cart between. */
    public static boolean isPortalPart(ServerLevel level, int carriageIndex) {
        return isPortalCarriage(level, carriageIndex) || isPortalMiddle(level, carriageIndex);
    }

    public static boolean isPortalCarriage(int carriageIndex, int groupSize, int every) {
        if (!isPortalGroup(carriageIndex, groupSize, every)) return false;
        int slot = slotOf(carriageIndex, groupSize);
        return slot == SLOT_ENTRY || slot == SLOT_EXIT;
    }

    public static boolean isPortalMiddle(int carriageIndex, int groupSize, int every) {
        if (!isPortalGroup(carriageIndex, groupSize, every)) return false;
        return slotOf(carriageIndex, groupSize) == SLOT_MIDDLE;
    }

    private static int every(ServerLevel level) {
        return PortalRegistry.get(level).carriageEvery();
    }
}
