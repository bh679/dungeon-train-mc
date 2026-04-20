package games.brennan.dungeontrain.train;

/**
 * Produces {@link CarriageSpec}s deterministically from a per-train {@code seed}
 * and a per-carriage {@code index}. Rolling-window carriages can be added or
 * re-added at any time; determinism guarantees index {@code N} always resolves
 * to the same spec for the lifetime of the train.
 *
 * <p>Design:
 * <ul>
 *   <li>Architecture: hash(seed, index) → one of 4</li>
 *   <li>Style: hash(seed, index / {@link #STYLE_RUN_LENGTH}) → one of 16.
 *       Carriages in the same run of 10 share a style.</li>
 *   <li>Contents: hash(seed, index, contents-salt) → one of 4, with a single
 *       re-roll if FLATBED + ENEMIES combo surfaces (mobs would fall off).</li>
 * </ul>
 */
public final class CarriageSpecGenerator {

    public static final int STYLE_RUN_LENGTH = 10;

    private static final long ARCH_SALT = 0x9E3779B97F4A7C15L;
    private static final long STYLE_SALT = 0xBF58476D1CE4E5B9L;
    private static final long CONTENTS_SALT = 0x94D049BB133111EBL;
    private static final long CONTENTS_REROLL_SALT = 0x2545F4914F6CDD1DL;

    private CarriageSpecGenerator() {}

    public static CarriageSpec specForIndex(int index, long seed) {
        CarriageArchitecture[] archs = CarriageArchitecture.values();
        CarriageStyle[] styles = CarriageStyle.values();

        CarriageArchitecture arch = archs[Math.floorMod(
            mix(seed, index, ARCH_SALT), archs.length
        )];

        int styleRun = Math.floorDiv(index, STYLE_RUN_LENGTH);
        CarriageStyle style = styles[Math.floorMod(
            mix(seed, styleRun, STYLE_SALT), styles.length
        )];

        // Contents disabled pending a future feature update. The plumbing
        // (CarriageContents enum, ContentsPopulator, populated-index tracking)
        // is intentionally kept so re-enabling is just a matter of restoring
        // the random roll here — see CONTENTS_SALT / CONTENTS_REROLL_SALT.
        CarriageContents c = CarriageContents.EMPTY;

        return new CarriageSpec(arch, style, c);
    }

    /** SplitMix64-style hash. Deterministic, uniform, fast. */
    private static int mix(long seed, int index, long salt) {
        long x = seed ^ (index * salt);
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        x = x ^ (x >>> 31);
        return (int) (x & 0x7FFFFFFF);
    }
}
