package games.brennan.dungeontrain.portal;

import net.minecraft.server.level.ServerLevel;

/**
 * Decides which carriages along the train are portal carriages: every {@code n}th carriage index,
 * with {@code n} persisted per world on {@link PortalRegistry} and tunable live via
 * {@code /dungeontrain portal carriage <n|off>}.
 *
 * <p>Index-based rather than random so the answer is stable: a carriage index yields the same
 * verdict on every reload and for every player, which matters because a carriage's blocks are
 * re-stamped whenever the rolling window brings it back round. A random pick would let a corridor
 * turn into an ordinary carriage under a player standing in it.</p>
 *
 * <p>The default is deliberately frequent — this is a test harness. Shipping portals every few
 * carriages is a gameplay decision that has not been made.</p>
 */
public final class PortalCarriageSelection {

    /** Every 4th carriage while testing. */
    public static final int DEFAULT_CARRIAGE_EVERY = 4;

    /** Value meaning "no carriage is a portal". */
    public static final int CARRIAGE_EVERY_OFF = 0;

    private PortalCarriageSelection() {}

    /**
     * Whether the carriage at this index should be stamped as a portal corridor.
     *
     * <p>Uses {@link Math#floorMod} because carriage indices go negative when the train extends
     * backwards, and {@code %} alone would make the spacing asymmetric about the origin.</p>
     */
    public static boolean isPortalCarriage(ServerLevel level, int carriageIndex) {
        int every = PortalRegistry.get(level).carriageEvery();
        if (every <= CARRIAGE_EVERY_OFF) return false;
        return Math.floorMod(carriageIndex, every) == 0;
    }
}
