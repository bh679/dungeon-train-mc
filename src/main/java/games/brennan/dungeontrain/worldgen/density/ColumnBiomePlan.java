package games.brennan.dungeontrain.worldgen.density;

import games.brennan.dungeontrain.worldgen.SecondLapOverworld;

/**
 * Per-thread <b>column memo</b> for the biome-source hook ({@code MultiNoiseBiomeSourceMixin}).
 *
 * <p>Vanilla asks {@code getNoiseBiome} once per quart — 1 536 times per chunk — but almost everything
 * DT decides there depends only on the column (block X, Z): the legacy-band override, the
 * Nether-core / End-core / highland verdict ({@link BandBiomeDecision}, y enters only as the
 * sea-level gate), the sampled core biome, and the stretch look. Recomputing those 96 times per
 * column was the {@code [gen.timing] biome=} cost. This memo computes them <em>once</em> per column
 * and answers the other 95 quarts from a 16-slot direct-mapped cache — one slot per (X, Z) quart of a
 * chunk, which vanilla's fill order (x outer, y middle, z inner, then the next section) walks fully
 * before any column repeats.</p>
 *
 * <p><b>Byte-identical by construction:</b> the memo stores the results of the very same pure
 * functions the per-quart path calls, for the same inputs. A slot is only reused when every input
 * those functions read is the same object / value as on the call that filled it: the published
 * {@link NetherBandContext}, the {@link OverworldStretchBiomes} tables, the memoised config cycle, the
 * legacy-biome context and the live reverse slide (a static the layout lookups read behind spawn
 * without touching the cycle cache — see {@code WorldGenCycle#reverseSlide}). Any publish, clear,
 * config reload or slide change therefore misses. Slots are published only after the whole record is
 * built, so a nested {@code getNoiseBiome} (the core samplers re-enter the hook on other sources) can
 * never observe a half-filled one.</p>
 *
 * <p>Generic over the biome holder type and free of Minecraft types so the memo is unit-testable
 * without a NeoForge bootstrap (same convention as {@link BetterNetherCoreBiomes}). {@link #ENABLED}
 * is the Gate 2 A/B seam ({@code /dungeontrain debug biome-memo on|off}) — OFF is the exact
 * pre-change per-quart path; same pattern as {@code BandEarlyOuts}.</p>
 */
public final class ColumnBiomePlan {

    /** Live A/B gate; {@code volatile} so worldgen workers observe a flip immediately. Default ON. */
    public static volatile boolean ENABLED = true;

    /** Quart columns per chunk edge; the slot table covers one chunk's {@code SIDE × SIDE} columns. */
    private static final int SIDE = 4;
    private static final int SLOTS = SIDE * SIDE;

    /**
     * The column-level answers the per-quart path derives everything from. {@code legacy} non-null
     * short-circuits the rest (a legacy band shows its own biome map at every height), so the other
     * fields are then unset. {@code core} is the sampled Nether-core / End-core biome when
     * {@code aboveSea} says so, else {@code null}. {@code look} is the stretch the column wears
     * (drives the BoP highland variant and the stretch pick).
     */
    public record Column<T>(int blockX, int blockZ,
                            Object ctx, Object stretchTables, Object cycle, Object legacyToken, long reverseSlide,
                            T legacy, BandBiomeDecision.Result aboveSea, int caveTop, T core,
                            SecondLapOverworld.Stretch look) {

        boolean matches(int blockX, int blockZ, Object ctx, Object stretchTables, Object cycle,
                        Object legacyToken, long reverseSlide) {
            return this.blockX == blockX && this.blockZ == blockZ
                    && this.ctx == ctx && this.stretchTables == stretchTables && this.cycle == cycle
                    && this.legacyToken == legacyToken && this.reverseSlide == reverseSlide;
        }
    }

    /**
     * The column-level lookups the memo fills a {@link Column} from — the live providers in
     * production, fakes in tests. Every method must be a pure function of its arguments plus the
     * state the validation keys identify.
     */
    public interface Providers<T> {
        /** The legacy-band biome for the column, or {@code null} ({@code LegacyBiomes#override}). */
        T legacy(int blockX, int blockZ);

        /** {@link BandBiomeDecision#decide} for any quart at or above sea level in this column. */
        BandBiomeDecision.Result decideAboveSea(int blockX, int blockZ);

        /** {@link BandBiomeDecision#caveWindowTop} for a HIGHLAND column (the cave band's top), else NO_CAVE. */
        int caveWindowTop(int blockX, int blockZ);

        /** The sampled real-Nether core biome for the column. */
        T netherCore(int blockX, int blockZ);

        /** The sampled real-End core biome for the column. */
        T endCore(int blockX, int blockZ);

        /** The stretch look the column wears ({@code SecondLapOverworld#lookAt}). */
        SecondLapOverworld.Stretch look(int blockX);
    }

    private static final ThreadLocal<Column<?>[]> SLOT_TABLE = ThreadLocal.withInitial(() -> new Column<?>[SLOTS]);

    private ColumnBiomePlan() {}

    /**
     * The memoised column for block {@code (blockX, blockZ)} under the given validation keys — served
     * from this thread's slot when it was filled for the same column and keys, else computed through
     * {@code providers} and stored. A stale slot is dropped before recomputing.
     */
    @SuppressWarnings("unchecked")
    public static <T> Column<T> column(int blockX, int blockZ,
                                       Object ctx, Object stretchTables, Object cycle, Object legacyToken,
                                       long reverseSlide, Providers<T> providers) {
        Column<?>[] slots = SLOT_TABLE.get();
        int slot = slotOf(blockX, blockZ);
        Column<?> cached = slots[slot];
        if (cached != null && cached.matches(blockX, blockZ, ctx, stretchTables, cycle, legacyToken, reverseSlide)) {
            return (Column<T>) cached;
        }
        slots[slot] = null;
        Column<T> fresh = compute(blockX, blockZ, ctx, stretchTables, cycle, legacyToken, reverseSlide, providers);
        slots[slot] = fresh;                       // publish only once fully built
        return fresh;
    }

    /** Slot for a column: its (X, Z) quart position within the chunk. */
    static int slotOf(int blockX, int blockZ) {
        int qx = (blockX >> 2) & (SIDE - 1);
        int qz = (blockZ >> 2) & (SIDE - 1);
        return qx * SIDE + qz;
    }

    /** Build the column record — the per-quart path's column-level calls, each made exactly once. */
    static <T> Column<T> compute(int blockX, int blockZ,
                                 Object ctx, Object stretchTables, Object cycle, Object legacyToken,
                                 long reverseSlide, Providers<T> providers) {
        T legacy = providers.legacy(blockX, blockZ);
        if (legacy != null) {
            return new Column<>(blockX, blockZ, ctx, stretchTables, cycle, legacyToken, reverseSlide,
                    legacy, BandBiomeDecision.Result.ORIGINAL, BandBiomeDecision.NO_CAVE, null, null);
        }
        BandBiomeDecision.Result aboveSea = providers.decideAboveSea(blockX, blockZ);
        T core = switch (aboveSea) {
            case NETHER_CORE -> providers.netherCore(blockX, blockZ);
            case END_CORE -> providers.endCore(blockX, blockZ);
            default -> null;
        };
        int caveTop = aboveSea == BandBiomeDecision.Result.HIGHLAND
                ? providers.caveWindowTop(blockX, blockZ) : BandBiomeDecision.NO_CAVE;
        SecondLapOverworld.Stretch look = providers.look(blockX);
        return new Column<>(blockX, blockZ, ctx, stretchTables, cycle, legacyToken, reverseSlide,
                null, aboveSea, caveTop, core, look);
    }

    /** Drop this thread's slots (tests). */
    static void clearThread() {
        java.util.Arrays.fill(SLOT_TABLE.get(), null);
    }
}
