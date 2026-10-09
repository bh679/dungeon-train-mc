package games.brennan.dungeontrain.client.replay;

import net.minecraft.world.phys.Vec3;

/**
 * Decides whether a tracked player's one-tick move during replay playback was a dimensional-carriage
 * swap the camera should follow.
 *
 * <p>Entering or leaving a dimensional carriage is a relative teleport of roughly a hundred blocks,
 * mostly vertical, into or out of the sealed twin below bedrock ({@code event.PortalCarriageEvents
 * .swapPlayers}). Nothing else in a ride moves a player that far in one tick: the train does
 * ~2 blocks a tick, an elytra or ender pearl a handful. So "far, and mostly up or down" is the
 * whole test. Pure, so it is unit-tested; the live caller adds the portal-region packet as a
 * second signal when the replay carried it.</p>
 */
public final class ReplayFollowRule {

    /** Minimum one-tick displacement, in blocks, that reads as a swap. */
    public static final double JUMP_BLOCKS = 24.0;

    /** Of which at least this much must be vertical — the twin is below (or, upside down, above). */
    public static final double JUMP_VERTICAL_BLOCKS = 16.0;

    private ReplayFollowRule() {}

    /** True if moving from {@code prev} to {@code now} in one tick looks like a carriage swap. */
    public static boolean isCarriageJump(Vec3 prev, Vec3 now) {
        if (prev == null || now == null) return false;
        double dy = Math.abs(now.y - prev.y);
        if (dy < JUMP_VERTICAL_BLOCKS) return false;
        return prev.distanceToSqr(now) >= JUMP_BLOCKS * JUMP_BLOCKS;
    }
}
