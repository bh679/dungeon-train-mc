package games.brennan.dungeontrain.train;

/**
 * How many carriage groups a spawn lane may add in a single tick while it is
 * BEHIND the players' needed carriage window. Stored in
 * {@code DungeonTrainCommonConfig} (one global value, runtime-editable) and
 * read by {@link TrainCarriageAppender#catchUpBurstGroups}.
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
     * <b>Default.</b> Not a pacing of its own: a stand-in that
     * {@link CatchUpBurstAuto#effectiveMode()} resolves to one of the three
     * below from the machine's specs, once, at first read.
     *
     * <p>It exists because the right answer depends on hardware. {@link #FILL}
     * spends about 30 ms of server tick per group for as long as the catch-up
     * lasts, on top of a baseline around 26 ms — comfortable on a desktop, over
     * the 50 ms budget on a thin laptop. A player should not have to know that
     * to get a sensible default.</p>
     *
     * <p>Never reaches {@link TrainCarriageAppender#catchUpBurstGroups}, which
     * rejects it: resolution happens above that call, and a mode that fell
     * through to the pacing logic would silently behave as {@link #BURST_TWO}.</p>
     */
    AUTO,

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
     * Add every group the lane is short, paced at
     * {@link TrainCarriageAppender#CATCH_UP_FILL_GROUPS_PER_TICK} per tick and
     * carried across ticks by a fill run, so the train closes the whole gap
     * rather than merely narrowing it.
     *
     * <p>The cost is therefore sustained rather than a single spike: roughly one
     * group's {@code TrainAssembler.spawnGroup} per tick for as many ticks as
     * the deficit is groups. That is what makes it the strongest mode on a
     * machine with tick headroom and the wrong one without — see {@link #AUTO}.</p>
     */
    FILL
}
