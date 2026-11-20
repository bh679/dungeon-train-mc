package games.brennan.dungeontrain.perks;

/**
 * Whether the world about to be created is a perks test world. Published by the title screen's
 * tester-only button (and carried by "Board anew" from one test world into the next), taken once by
 * {@code WorldLifecycleEvents} and written into {@code DungeonTrainWorldData} only for a brand-new
 * world, so a value left over from a cancelled launch never turns perks on in an existing save.
 *
 * <p>Free of client-only imports: the server side of an integrated game reads it.</p>
 */
public final class PendingPerksTest {

    private static volatile boolean pending;

    private PendingPerksTest() {}

    /** The next new world is a perks test world (or, with {@code false}, is not). */
    public static void set(boolean perksTest) {
        pending = perksTest;
    }

    /** The published answer; clears the holder. */
    public static boolean take() {
        boolean taken = pending;
        pending = false;
        return taken;
    }
}
