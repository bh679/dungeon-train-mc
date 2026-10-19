package games.brennan.dungeontrain.wind;

/**
 * The wind as a <b>pure deterministic function</b> of (blocks past the Upside-down end, game tick, seed).
 *
 * <p>Being deterministic is the whole design: the server pushes non-player entities at {@code tick}, each
 * client pushes its own player at {@code tick} (player movement is client-authoritative) and draws the
 * streaks at {@code tick + visualLeadTicks} — so the visuals lead the push by exactly that much, with no
 * per-tick packet. The only sync is the profile + seed, once.</p>
 *
 * <p><b>Mirrored line-for-line in {@code scripts/wind/web/wind.js}</b> (the tuner). Keep the arithmetic
 * identical — only {@code +,-,*,/}, {@code floor}, 32-bit integer hashing — so both sides agree to the
 * last bit; {@code WindFieldTest} pins golden vectors that the tuner page checks too.</p>
 *
 * <ul>
 *   <li><b>Steady</b>: the stage's {@code base}, wobbling slowly by ±{@code flutter}.</li>
 *   <li><b>Gusts</b>: time is cut into 1-second slots; each slot fires a gust with probability
 *       {@code gustsPerMin / 60}, at a hashed offset, lasting a hashed length between
 *       {@link #MIN_GUST_SECONDS} and the stage's longest ({@code gustTicks}) (quick rise, slow fall).
 *       Overlapping gusts take the strongest.</li>
 *   <li><b>Heading</b>: {@code headingDeg} plus two-octave value noise of amplitude {@code wander} and
 *       period {@code wanderSeconds}, plus a gust's own veer at its peak.</li>
 * </ul>
 */
public final class WindField {

    /** Gust slot length, ticks. */
    static final int GUST_SLOT = 20;
    /** Fraction of a gust spent rising. */
    static final double GUST_ATTACK = 0.25;
    /** Flutter noise period, ticks. */
    static final double FLUTTER_PERIOD = 40.0;
    /** The shortest a gust can be, seconds; each gust rolls its length between this and the stage's longest. */
    public static final double MIN_GUST_SECONDS = 0.1;
    private static final double MIN_GUST_TICKS = MIN_GUST_SECONDS * 20.0;
    private static final double DEG = Math.PI / 180.0;

    private static final int SALT_WANDER = 0x1F3D5B79;
    private static final int SALT_WANDER2 = 0x2A4C6E80;
    private static final int SALT_FLUTTER = 0x3B5D7F91;
    private static final int SALT_FIRE = 1;
    private static final int SALT_OFFSET = 2;
    private static final int SALT_POWER = 3;
    private static final int SALT_VEER = 4;
    private static final int SALT_NOISE = 7;
    private static final int SALT_LENGTH = 5;

    private WindField() {}

    /** The wind {@code d} blocks past the Upside-down end at game tick {@code tick}. */
    public static WindSample sample(WindProfile profile, double d, long tick, int seed) {
        if (!profile.enabled() || !(d >= 0.0) || profile.curves() == null) return WindSample.CALM;
        WindProfile.Stage s = profile.stageAt(d);
        if (s == null) return WindSample.CALM;
        double t = (double) tick;

        // Heading drift.
        double period = Math.max(20.0, s.wanderSeconds() * 20.0);
        double n = noise(seed ^ SALT_WANDER, t / period) + 0.35 * noise(seed ^ SALT_WANDER2, t / (period / 3.0));
        double headingDeg = profile.headingDeg() + s.wander() * (n / 1.35);

        // Steady push with flutter.
        double fl = noise(seed ^ SALT_FLUTTER, t / FLUTTER_PERIOD);
        double steady = Math.max(0.0, s.base() * (1.0 + s.flutter() * fl));

        // Gusts.
        double p = Math.min(1.0, s.gustsPerMin() * GUST_SLOT / 1200.0);
        double longest = Math.max(MIN_GUST_TICKS, s.gustTicks());
        double gust = 0.0;
        double veer = 0.0;
        if (p > 0.0 && s.gustStrength() > 0.0) {
            long k = Math.floorDiv(tick, (long) GUST_SLOT);
            long back = (long) Math.ceil(longest / GUST_SLOT) + 1L;
            for (long j = k - back; j <= k; j++) {
                if (hash01(seed, j, SALT_FIRE) >= p) continue;
                double start = (double) j * GUST_SLOT + hash01(seed, j, SALT_OFFSET) * GUST_SLOT;
                double dur = MIN_GUST_TICKS + hash01(seed, j, SALT_LENGTH) * (longest - MIN_GUST_TICKS);
                double u = (t - start) / dur;
                if (u < 0.0 || u >= 1.0) continue;
                double env = u < GUST_ATTACK ? smooth(u / GUST_ATTACK) : smooth((1.0 - u) / (1.0 - GUST_ATTACK));
                double g = s.gustStrength() * (0.6 + 0.4 * hash01(seed, j, SALT_POWER)) * env;
                if (g > gust) {
                    gust = g;
                    veer = (hash01(seed, j, SALT_VEER) * 2.0 - 1.0) * s.gustVeer() * env;
                }
            }
        }

        double strength = steady + gust;
        if (strength <= 0.0) return WindSample.CALM;
        double gust01 = Math.min(1.0, gust / strength);
        return new WindSample(strength, (headingDeg + veer) * DEG, gust01);
    }

    /** Smooth value noise in [-1, 1]. */
    static double noise(int seed, double x) {
        double fi = Math.floor(x);
        double f = x - fi;
        long i = (long) fi;
        double a = hash01(seed, i, SALT_NOISE) * 2.0 - 1.0;
        double b = hash01(seed, i + 1L, SALT_NOISE) * 2.0 - 1.0;
        return a + (b - a) * smooth(f);
    }

    static double smooth(double f) {
        return f * f * (3.0 - 2.0 * f);
    }

    /** 32-bit integer hash of (seed, cell, salt) to [0, 1). {@code cell} is truncated to 32 bits. */
    static double hash01(int seed, long cell, int salt) {
        int h = seed ^ ((int) cell * 0x9E3779B1) ^ (salt * 0x85EBCA77);
        h ^= h >>> 16;
        h *= 0x7FEB352D;
        h ^= h >>> 15;
        h *= 0x846CA68B;
        h ^= h >>> 16;
        return (h & 0xFFFFFFFFL) / 4294967296.0;
    }

    /** Fold a 64-bit world seed to the 32-bit seed {@link #sample} takes. */
    public static int foldSeed(long worldSeed) {
        return (int) (worldSeed ^ (worldSeed >>> 32));
    }
}
