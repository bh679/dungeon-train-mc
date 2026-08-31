package games.brennan.dungeontrain.train;

/**
 * How many carriage groups a spawn lane may add in a single tick while it is
 * BEHIND the players' needed carriage window. Stored in
 * {@code DungeonTrainConfig} (runtime-editable) and read by
 * {@link TrainCarriageAppender#catchUpBurstGroups}.
 *
 * <p>Context: each lane normally adds one group per settle window, because the
 * placement tracker needs a settled neighbour before the next group can be
 * landed inside the [{@code MIN_GAP_BLOCKS}, {@code MAX_GAP_BLOCKS}] seam band.
 * That pacing is what keeps seams even — and also what lets a fast train
 * outrun the player. These modes choose what happens when a lane falls behind;
 * none of them changes the steady state, where the lane is at most one group
 * short and every mode adds exactly one group.</p>
 */
public enum CatchUpBurstMode {
    /**
     * Never add more than one group per lane per settle window — the behaviour
     * from before catch-up spawning existed. Seams are paced by the tracker at
     * all times; a lane that falls behind stays behind until the player slows
     * down or stops.
     */
    OFF,

    /**
     * Add two groups in one tick while the lane is
     * {@link TrainCarriageAppender#CATCH_UP_DEFICIT_GROUPS} or more groups
     * short. Doubles catch-up throughput for one extra group's spawn cost per
     * settle window; a lane far behind closes the gap gradually rather than at
     * once.
     */
    BURST_TWO,

    /**
     * <b>Default.</b> Add as many groups as the lane is short, in the one tick
     * — the train catches up immediately. Pays every one of those groups'
     * {@code TrainAssembler.spawnGroup} cost on a single server tick, so a
     * large fill is a deliberate one-off hitch traded for the train never
     * being seen to run away.
     */
    FILL
}
