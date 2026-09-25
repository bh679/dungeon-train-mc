package games.brennan.dungeontrain.worldgen.density;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Picks the BetterNether biome for a Nether-<b>core</b> column on the alternate (odd-pass) Nether
 * bands. Even passes stay vanilla — see {@link NetherCoreBiomes#biomeAt(int, int, long)} for the split
 * and {@link #isBetterNetherPass} for which passes this covers.
 *
 * <p>BetterNether registers its biomes into the real Nether through WorldWeaver's biome source, but DT
 * cannot sample that source per-pass without the vanilla biomes leaking back in. So this is DT's own
 * picker. The core is cut into {@link #CELL_BLOCKS}-block cells along X. Each cell takes the next biome
 * from a seeded shuffle of the list, so a band walks through distinct biomes with no two neighbours
 * alike. Cell edges are waved along Z so a border reads as terrain, not a straight line. The result is
 * deterministic from the world seed alone, which keeps the world label, surface skin and decoration in
 * agreement.</p>
 *
 * <p>Generic over the entry type so the pure selection logic is unit-testable without a registry;
 * production uses {@code Holder<Biome>}.</p>
 */
public final class BetterNetherCoreBiomes<T> {

    /** Namespace BetterNether's biomes are registered under. */
    public static final String NAMESPACE = "betternether";

    /** Core-X length of one biome cell. A 5000-block default core crosses ~16 cells. */
    public static final int CELL_BLOCKS = 300;

    /** Peak Z-driven wobble (blocks) of a cell border, so borders aren't straight lines. */
    private static final double BORDER_WAVE_BLOCKS = 32.0;

    /**
     * BetterNether "sub-biome" path suffixes: edge rings and variants that BetterNether only places
     * nested inside a parent biome. Standing alone as a full cell they read as a broken parent.
     */
    private static final List<String> SUB_BIOME_SUFFIXES = List.of("_edge", "_terraces", "_cleared");

    private final List<T> order;
    private final double wavePhase;

    private BetterNetherCoreBiomes(List<T> order, double wavePhase) {
        this.order = order;
        this.wavePhase = wavePhase;
    }

    /**
     * Build a picker over {@code biomes} in a seeded order. Returns {@code null} for an empty list so
     * callers fall back to vanilla. The input is copied; its order doesn't matter, because the caller's
     * registry iteration order isn't guaranteed stable, so callers must pass a list sorted by id.
     */
    public static <T> BetterNetherCoreBiomes<T> of(List<T> sortedBiomes, long seed) {
        if (sortedBiomes == null || sortedBiomes.isEmpty()) return null;
        List<T> shuffled = new ArrayList<>(sortedBiomes);
        Random random = new Random(seed ^ 0x6E65_7468_6572_4C4EL);
        Collections.shuffle(shuffled, random);
        return new BetterNetherCoreBiomes<>(List.copyOf(shuffled), random.nextDouble() * Math.PI * 2.0);
    }

    /** True for a BetterNether biome path that should get a full cell (not an edge/variant sub-biome). */
    public static boolean isStandaloneBiomePath(String path) {
        if (path == null || path.isEmpty()) return false;
        for (String suffix : SUB_BIOME_SUFFIXES) {
            if (path.endsWith(suffix)) return false;
        }
        return true;
    }

    /**
     * The <b>classic</b> (blank-order) layout's rule: alternate Nether bands use BetterNether — pass 1,
     * 3, 5… (the 2nd, 4th, 6th band). Pass 0 and every even pass stay vanilla, as does {@code -1} (before
     * the cycle anchor). A {@code worldgenCycleOrder} layout instead follows each slot's own style — always
     * ask {@link games.brennan.dungeontrain.worldgen.WorldGenCycle#isBetterNetherPass}, which picks the rule.
     */
    public static boolean isBetterNetherPass(long passIndex) {
        return passIndex > 0 && (passIndex & 1L) == 1L;
    }

    /** The biome for a core column at this world XZ. */
    public T biomeAt(int worldX, int worldZ) {
        int cell = cellIndex(worldX, worldZ);
        return order.get(Math.floorMod(cell, order.size()));
    }

    /** Number of biomes this picker cycles through. */
    public int size() {
        return order.size();
    }

    int cellIndex(int worldX, int worldZ) {
        double wave = BORDER_WAVE_BLOCKS * (0.65 * Math.sin(worldZ * 0.041 + wavePhase)
                + 0.35 * Math.sin(worldZ * 0.013 + wavePhase * 1.7));
        return Math.floorDiv(worldX + (int) Math.round(wave), CELL_BLOCKS);
    }
}
