package games.brennan.dungeontrain.worldgen;

/**
 * What the End-band repair sweep ({@code WorldEndBandEvents}) does with one loaded chunk near a player.
 * Free of Minecraft types so it is unit-testable.
 *
 * <p>A band chunk is flagged pending until its sampled terrain is written, and asks for that terrain only
 * when it loads. Anything that loses the request while the chunk <em>stays</em> loaded left a permanent
 * square of void: a job dropped because no player was near yet (a {@code /dtp} spawns the train, and loads
 * its chunks, seconds before the player lands), or a sample that finished between the chunk's load event
 * and the chunk counting as loaded, and was stashed for a load that had already happened. The sweep
 * re-checks the chunks players can see, so every such chunk is written after all.</p>
 */
public final class PendingChunkSweep {

    /** The sweep's decision for one chunk. */
    public enum Action {
        /** Nothing owed, or already on its way. */
        NONE,
        /** Its finished sample is waiting in the stash: write that. */
        WRITE_STASHED,
        /** Ask for its sample again (a no-op if one is already in flight). */
        REQUEST
    }

    private PendingChunkSweep() {}

    /**
     * @param pending  the chunk still carries the End-band pending flag
     * @param stashed  a finished sample for it waits in the stash
     * @param due      its sample is already queued to be written next tick
     */
    public static Action decide(boolean pending, boolean stashed, boolean due) {
        if (!pending || due) return Action.NONE;
        return stashed ? Action.WRITE_STASHED : Action.REQUEST;
    }
}
