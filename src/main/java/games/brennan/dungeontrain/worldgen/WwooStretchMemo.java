package games.brennan.dungeontrain.worldgen;

/**
 * Per-thread <b>block-X memo</b> for "is this X outside the WWOO stretch?" — the question
 * {@link VanillaBiomeTwins#twinFor} asks for every tinted block (grass, foliage, water, with the
 * biome-blend radius, from every chunk-mesh thread) and for every snow/ice check on worldgen workers.
 *
 * <p>Uncached, each call ran {@link SecondLapOverworld#lookAt}: a layout slot search for the stretch
 * plus another for the band-transition bleed. Neighbouring blocks, blend samples and the 16 rows of a
 * column all ask about the same few X, so this answers repeats from a 64-slot direct-mapped table —
 * a chunk's 16 columns plus a blend radius either side as a mesh thread sweeps X.</p>
 *
 * <p><b>Byte-identical by construction</b> (same idea as {@code density.ColumnBiomePlan}): a slot
 * stores the very answer {@code lookAt} gave, and is reused only when every input {@code lookAt}
 * reads is the same — block X, the cycle instance (identity; any publish, clear or config reload
 * hands out a new one) and the live {@link WorldGenCycle#reverseSlide()}, which the layout reads
 * behind the anchor without the cycle changing. Keyed on block X, not the chunk column: stretch and
 * bleed edges are not chunk-aligned. Entries are immutable and stored whole.</p>
 *
 * <p>Free of Minecraft types so it is unit-testable without a NeoForge bootstrap.</p>
 */
public final class WwooStretchMemo {

    private static final int SLOTS = 64;
    private static final int MASK = SLOTS - 1;

    private record Entry(int blockX, WorldGenCycle cycle, long reverseSlide, boolean outside) {}

    private static final ThreadLocal<Entry[]> TABLE = ThreadLocal.withInitial(() -> new Entry[SLOTS]);

    private WwooStretchMemo() {}

    /** True when block {@code blockX} does not wear the WWOO look under {@code cycle}: the vanilla twin answers. */
    public static boolean outside(WorldGenCycle cycle, int blockX) {
        long slide = WorldGenCycle.reverseSlide();
        Entry[] table = TABLE.get();
        int slot = blockX & MASK;
        Entry e = table[slot];
        if (e != null && e.blockX == blockX && e.cycle == cycle && e.reverseSlide == slide) {
            return e.outside;
        }
        boolean outside = uncached(cycle, blockX);
        table[slot] = new Entry(blockX, cycle, slide, outside);
        return outside;
    }

    /** The un-memoised answer — what {@link #outside} must always equal. */
    static boolean uncached(WorldGenCycle cycle, int blockX) {
        return SecondLapOverworld.lookAt(cycle, blockX) != SecondLapOverworld.Stretch.WWOO;
    }

    /** Drop this thread's slots (tests). */
    static void clearThread() {
        java.util.Arrays.fill(TABLE.get(), null);
    }
}
