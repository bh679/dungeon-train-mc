package games.brennan.dungeontrain.nap;

/**
 * Which world a nap wakes in. {@link NapWake#planFor} binds a nap to the new world's save folder
 * ({@link NapSnapshot#wakeLevelId}) before the train spawns there, so a nap can only ever wake in
 * one world: pausing (which saves) and crashing mid-load can't leave the gear saved in one world
 * and the nap free to wake in the next.
 */
public final class NapBinding {

    /** What the server starting in a world does with a nap. */
    public enum Decision {
        /** Wake it here (and bind it here first). */
        WAKE,
        /** It belongs to another world; leave it alone. */
        IGNORE,
        /** Its life is already saved in this world: mark it used and delete it. */
        CONSUME
    }

    private NapBinding() {}

    /**
     * @param thisLevelId        save folder of the world starting now
     * @param boundWorldHolds    whether the nap's bound world is still on disk with the owner's
     *                           player saved in it ({@link NapBoundWorld}; ignored when unbound or
     *                           bound here). A bound world the player never saved into holds nothing
     * @param thisHasSavedPlayer whether this world already holds saved player data for the owner
     */
    public static Decision decide(NapSnapshot nap, String thisLevelId, boolean boundWorldHolds,
                                  boolean thisHasSavedPlayer) {
        if (!nap.bound()) return Decision.WAKE;
        if (nap.wakeLevelId().equals(thisLevelId)) {
            // Reopened (only through crash recovery): if the player saved here, the gear is here.
            return thisHasSavedPlayer ? Decision.CONSUME : Decision.WAKE;
        }
        // Bound to a world that is gone, or that the game died in before the player was saved:
        // nothing holds the life, so it wakes (and rebinds) in the next one.
        return boundWorldHolds ? Decision.IGNORE : Decision.WAKE;
    }

    /** Whether the title screen should offer "Reboard" for {@code nap}. */
    public static boolean waitsForNewWorld(NapSnapshot nap, boolean boundWorldHolds) {
        return !nap.bound() || !boundWorldHolds;
    }
}
