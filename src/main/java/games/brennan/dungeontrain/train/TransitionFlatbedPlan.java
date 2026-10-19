package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.worldgen.TransitionLandmarks;

/**
 * The pure maths behind {@link TransitionFlatbeds}: which carriage index a transition landmark's flatbed
 * goes to. No Minecraft types, so it unit-tests directly.
 *
 * <p>Every landmark is timed. The train moves +X at {@code v} blocks/s and the lead player walks forward
 * through it at {@code w} carriages/s — {@code w·pitch} blocks/s on top of the train. Standing in carriage
 * {@code p0} at world X {@code x0}, they reach the landmark at {@code X} after
 * {@code Δt = (X − x0) / (v + w·pitch)} seconds, by which time they have walked {@code w·Δt} carriages: the
 * flatbed goes to {@code p0 + round(w·Δt)}. A player who stops to fight still reaches the landmark — the
 * train carries them — so {@code w} is an average that includes pauses ({@link PlayerCarriagePace}).</p>
 *
 * <p>Carriages ahead are only spawned as the player walks forward, so the decision is made when the
 * group holding the predicted index is spawned. If the prediction has already slid behind that group
 * (the player sped up) the flatbed lands on the first carriage still to be built — a little late rather
 * than skipped — unless it would be more than {@link #LATE_SLACK_CARRIAGES} late. That slack is also what
 * carries a target that fell in a portal group (which takes no claim) on to the next group.</p>
 */
public final class TransitionFlatbedPlan {

    /** Pace floor (carriages/s): an idle player's prediction still runs ahead of them instead of onto their own carriage. */
    public static final double MIN_PACE = 0.05;

    /** How many carriages late a timed flatbed may land before it is dropped as missed. */
    public static final int LATE_SLACK_CARRIAGES = 6;

    /** Sentinel for {@link #predictTarget}: the player never reaches the landmark (train and player both still). */
    public static final int UNREACHABLE = Integer.MAX_VALUE;

    /** The player the prediction follows: their carriage, world X and pace (carriages/s). */
    public record Lead(int pIdx, double worldX, double pace) {}

    private TransitionFlatbedPlan() {}

    /**
     * The carriage the lead player is predicted to stand in when they reach world X {@code landmarkX}.
     *
     * @param speed train speed, blocks/s
     * @param pace  the player's pace through the train, carriages/s (floored at {@link #MIN_PACE})
     * @param pitch world blocks per carriage along the train (group stride ÷ group size)
     */
    public static int predictTarget(int p0, double x0, long landmarkX, double speed, double pace, double pitch) {
        double dist = landmarkX - x0;
        if (dist <= 0.0) return p0;
        double w = Math.max(MIN_PACE, pace);
        double closing = Math.max(0.0, speed) + w * Math.max(0.0, pitch);
        if (closing <= 1e-9) return UNREACHABLE;
        double carriages = w * dist / closing;
        if (carriages >= UNREACHABLE - (double) p0) return UNREACHABLE;
        return p0 + (int) Math.round(carriages);
    }

    /**
     * The carriage indices landmark {@code lm} claims when the forward group {@code [anchor, anchor+groupSize)}
     * is about to spawn, or {@code null} when it is not this group's to decide (not reached yet, already
     * passed, missed). The claim can reach past the group (three in a row) — later groups honour it.
     *
     * @param stride            one whole group's length along X (pads and seam gap included)
     */
    public static int[] claimFor(TransitionLandmarks.Landmark lm, Lead lead, double speed,
                                 int anchor, int groupSize, double stride) {
        int count = Math.max(1, lm.count());
        int groupEnd = anchor + groupSize - 1;
        if (lead == null || lead.worldX() >= lm.worldX()) return null;
        double pitch = stride / Math.max(1, groupSize);
        int target = predictTarget(lead.pIdx(), lead.worldX(), lm.worldX(), speed, lead.pace(), pitch);
        if (target == UNREACHABLE || target > groupEnd) return null;
        if (anchor - target > LATE_SLACK_CARRIAGES) return null;
        int first = Math.max(anchor, target - count + 1);
        return run(first, count);
    }

    private static int[] run(int first, int count) {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) out[i] = first + i;
        return out;
    }
}
