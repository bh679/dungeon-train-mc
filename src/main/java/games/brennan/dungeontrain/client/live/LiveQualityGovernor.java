package games.brennan.dungeontrain.client.live;

import java.util.Arrays;
import java.util.OptionalInt;

/**
 * Picks the stream's video bitrate from how well the streamer's upload keeps up ({@link LiveUploadHealth}).
 * Pure, so the rules are unit-tested without a game.
 *
 * <p>A ladder of bitrates below the player's own setting (the ceiling: never exceeded). It steps
 * <b>down</b> as soon as the upload is nearly saturated ({@value #DOWN_UTILISATION} of real time),
 * straight to the rung the measured speed carries with room to spare. It steps <b>up</b> one rung at a
 * time, only after {@value #UP_AFTER_MS} ms on a rung and only when the next one would still leave the
 * upload mostly idle. A cooldown after every switch lets the new rate's segments speak first.</p>
 *
 * <p>Within a stream only the bitrate changes; the picture size is chosen once, at the start, from the
 * speed remembered from earlier streams ({@link #startWidth}).</p>
 */
public final class LiveQualityGovernor {

    /** Rungs as shares of the ceiling. */
    static final double[] LADDER = {1.0, 0.8, 0.62, 0.48, 0.36, 0.26, 0.2};
    /** Step down when uploads take this share of real time (1 = falling behind). */
    static final double DOWN_UTILISATION = 0.9;
    static final int DOWN_MIN_SAMPLES = 2;
    /** A rung fits a measured speed when the whole stream (video and sound) is at most this share of it. */
    static final double FIT_SHARE = 0.75;
    /** Step up only when the next rung would keep uploads under this share of real time. */
    static final double UP_UTILISATION = 0.55;
    static final int UP_MIN_SAMPLES = 6;
    static final long UP_AFTER_MS = 180_000;
    static final long COOLDOWN_MS = 45_000;
    /** Remembered speeds under this start the stream at {@link #LOW_SPEED_WIDTH} wide. */
    static final int LOW_SPEED_KBPS = 1300;
    static final int LOW_SPEED_WIDTH = 854;

    private final int[] rungs;
    private final int audioKbps;
    private int rung;
    private long switchedAtMs;

    /** {@code ceilingKbps}: the player's video bitrate; {@code startKbps}: where this stream begins ({@link #startKbps}). */
    public LiveQualityGovernor(int ceilingKbps, int startKbps, int audioKbps, long nowMs) {
        this.rungs = ladder(ceilingKbps);
        this.audioKbps = Math.max(0, audioKbps);
        this.rung = indexAtOrBelow(rungs, startKbps);
        this.switchedAtMs = nowMs;
    }

    /** Distinct bitrates from {@code ceilingKbps} down, none below {@link LiveEncoder#MIN_BITRATE_KBPS}. */
    static int[] ladder(int ceilingKbps) {
        int ceiling = Math.max(LiveEncoder.MIN_BITRATE_KBPS, ceilingKbps);
        return Arrays.stream(LADDER)
            .mapToInt(share -> Math.max(LiveEncoder.MIN_BITRATE_KBPS, (int) Math.round(ceiling * share)))
            .distinct()
            .toArray();
    }

    /**
     * The bitrate a stream starts at: the ceiling, or with a speed {@code learnedKbps} remembered from
     * earlier streams (0 = none), the highest rung that speed carries.
     */
    public static int startKbps(int ceilingKbps, int learnedKbps, int audioKbps) {
        int[] rungs = ladder(ceilingKbps);
        return learnedKbps <= 0 ? rungs[0] : rungs[fittingRung(rungs, learnedKbps, audioKbps)];
    }

    /** The picture width a stream starts at: the player's own, or {@value #LOW_SPEED_WIDTH} on a slow remembered link. */
    public static int startWidth(int configuredWidth, int learnedKbps) {
        if (learnedKbps > 0 && learnedKbps < LOW_SPEED_KBPS) return Math.min(configuredWidth, LOW_SPEED_WIDTH);
        return configuredWidth;
    }

    /** The current video bitrate. */
    public int kbps() {
        return rungs[rung];
    }

    /** True when below the player's own bitrate. */
    public boolean lowered() {
        return rung > 0;
    }

    /** A new video bitrate when {@code health} calls for one (and the governor now stands on it), else empty. */
    public OptionalInt decide(LiveUploadHealth.Snapshot health, long nowMs) {
        if (nowMs - switchedAtMs < COOLDOWN_MS) return OptionalInt.empty();
        int last = rungs.length - 1;
        if (rung < last && health.samples() >= DOWN_MIN_SAMPLES && health.utilisation() > DOWN_UTILISATION) {
            int target = health.throughputKbps() > 0 ? fittingRung(rungs, health.throughputKbps(), audioKbps) : last;
            return switchTo(Math.max(target, rung + 1), nowMs);
        }
        if (rung > 0 && nowMs - switchedAtMs >= UP_AFTER_MS && health.samples() >= UP_MIN_SAMPLES) {
            double predicted = health.utilisation() * (rungs[rung - 1] + audioKbps) / (double) (rungs[rung] + audioKbps);
            if (predicted < UP_UTILISATION) return switchTo(rung - 1, nowMs);
        }
        return OptionalInt.empty();
    }

    private OptionalInt switchTo(int target, long nowMs) {
        rung = target;
        switchedAtMs = nowMs;
        return OptionalInt.of(rungs[rung]);
    }

    /** The highest rung whose stream (video + sound) is at most {@value #FIT_SHARE} of {@code speedKbps}; the lowest if none. */
    private static int fittingRung(int[] rungs, int speedKbps, int audioKbps) {
        for (int i = 0; i < rungs.length; i++) {
            if (rungs[i] + audioKbps <= FIT_SHARE * speedKbps) return i;
        }
        return rungs.length - 1;
    }

    private static int indexAtOrBelow(int[] rungs, int kbps) {
        for (int i = 0; i < rungs.length; i++) {
            if (rungs[i] <= kbps) return i;
        }
        return rungs.length - 1;
    }
}
