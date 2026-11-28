package games.brennan.dungeontrain.perks.effects.hoarder;

import java.util.List;

/**
 * Client-side copy of the last {@link ChestSensePacket}: what to outline and until when (wall-clock
 * millis, so a paused game still fades on time), and the particle ring's spread. Plain fields, no client classes, so the packet
 * handler can live in common code.
 */
public final class ChestSenseClientState {

    private static volatile List<ChestSense.Sensed> sensed = List.of();
    private static volatile long untilMillis = 0L;
    private static volatile long startMillis = 0L;
    private static volatile float ringRange = 0F;

    private ChestSenseClientState() {}

    /** How long the particle ring takes to spread from the player to the range. */
    public static final long RING_MILLIS = 1000L;

    static void set(List<ChestSense.Sensed> found, int durationTicks, float range) {
        long now = System.currentTimeMillis();
        ringRange = Math.max(0F, range);
        sensed = List.copyOf(found);
        startMillis = now;
        untilMillis = now + durationTicks * 50L;
    }

    /** What to outline now; empty once the glow is over. */
    public static List<ChestSense.Sensed> active() {
        return System.currentTimeMillis() < untilMillis ? sensed : List.of();
    }

    /** 1 at the pulse, falling to 0 as the glow ends; for fading the outline. */
    public static float strength() {
        long now = System.currentTimeMillis();
        long span = untilMillis - startMillis;
        if (span <= 0 || now >= untilMillis) return 0F;
        return Math.min(1F, (untilMillis - now) / (float) span * 2F);
    }

    /** The particle ring's radius now, or -1 once it has reached the range. */
    public static float ringRadius() {
        long elapsed = System.currentTimeMillis() - startMillis;
        if (startMillis == 0L || elapsed < 0 || elapsed > RING_MILLIS) return -1F;
        return ringRange * elapsed / (float) RING_MILLIS;
    }
}
