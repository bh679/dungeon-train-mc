package games.brennan.dungeontrain.worldgen;

/**
 * The shipped-order {@link WorldGenCycle} for tests outside this package — the same cycle
 * {@link WorldGenCycleLayoutTest} drives, built from {@link CycleLayoutTest#shipped()}.
 */
public final class ShippedCycles {

    /** World X where run 0 begins. */
    public static final long START = 10_000L;

    private static final CycleLayout LAYOUT = CycleLayoutTest.shipped();

    /** The shipped defaults: stage 40 × {1,2,4,8,15}, beach 32, core fade 300; End 120/500; UD 600, exit 600, exit fade 10 000. */
    public static final WorldGenCycle CYCLE = new WorldGenCycle(START, 10_000, 40, new int[] {1, 2, 4, 8, 15}, 32, 0, 300, 5000,
            120, 500, 5000, 600, 5000, 600, 10_000, 8000, 1500, 5000, 0.3, 0.4, 6550, 750, 5000, 8000, 1500, 10_000, 0.08,
            CycleLayoutTest.eraDefaults(), LAYOUT, 0);

    /** Index of Lap 1's split Nether slot ({@code nether:vanilla=1000~400+bop}). */
    private static final int SPLIT_NETHER_SLOT = 1;

    private ShippedCycles() {}

    /** World X of the first block of run 0's vanilla → BoP Nether mix. */
    public static int netherMixStartX() {
        return (int) (START + LAYOUT.netherCoreStart(SPLIT_NETHER_SLOT) + LAYOUT.slot(SPLIT_NETHER_SLOT).splitAt());
    }

    /** Length in blocks of that mix. */
    public static int netherMixLength() {
        return LAYOUT.slot(SPLIT_NETHER_SLOT).splitBlend();
    }
}
