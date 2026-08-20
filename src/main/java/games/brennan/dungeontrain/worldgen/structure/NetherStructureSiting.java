package games.brennan.dungeontrain.worldgen.structure;

import games.brennan.dungeontrain.worldgen.NetherCoreGeometry;
import games.brennan.dungeontrain.worldgen.NetherMountainTerrain;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;

/**
 * The pure "may a Nether structure stand here, and at what Y" decision behind the band's
 * {@code Band*Structure} classes. Extracted from them (and free of Minecraft types) so the rules that
 * decide where a fortress, bastion, fossil or ruined portal may land are unit-testable without a NeoForge
 * bootstrap — the same split as {@link EndCitySiting} for End cities.
 *
 * <p>Two rules, and they are the whole of it:</p>
 * <ul>
 *   <li><b>Where.</b> Only real-Nether <b>core</b> columns, tested at the <b>edge-waved</b> X
 *       ({@link NetherMountainTerrain#wavyX}) — the band's front undulates by up to
 *       {@link NetherMountainTerrain#maxEdgeShift()} blocks, and terrain, biome and the core fill all
 *       evaluate it there. A siting test that used the raw X would disagree with the terrain by up to that
 *       much at the boundary and drop structures into the crossfade. The End band always wins any overlap,
 *       exactly as {@code NetherTransitionFeature} yields those columns.</li>
 *   <li><b>How high.</b> The Nether's own Y for the structure, translated onto track level by the same
 *       {@code bedY} ↔ {@link NetherCoreGeometry#NETHER_CENTER_Y} mapping the core terrain uses, then
 *       clamped into the sampled slab. Outside that slab there is no core terrain at all — a piece there
 *       would hang in raw overworld air (and below the world floor it is dropped outright by
 *       {@code StructureBasementMixin}).</li>
 * </ul>
 */
public final class NetherStructureSiting {

    private NetherStructureSiting() {}

    /**
     * Band world Y for a Nether-space Y — the Nether's own placement heights (a fortress's 48–70, a
     * bastion's 33–100) land on the band exactly where the core terrain put that slice of Nether.
     */
    public static int bandY(int bedY, int netherY) {
        return bedY + (netherY - NetherCoreGeometry.NETHER_CENTER_Y);
    }

    /** Lowest band world Y that carries core terrain. */
    public static int minStructureY(int bedY) {
        return bandY(bedY, NetherCoreGeometry.NETHER_Y_SAMPLE_MIN);
    }

    /** Highest band world Y that carries core terrain. */
    public static int maxStructureY(int bedY) {
        return bandY(bedY, NetherCoreGeometry.NETHER_Y_SAMPLE_MAX);
    }

    /** {@code y} clamped into the band's core slab, so a piece always has terrain to sit in. */
    public static int clampToCore(int bedY, int y) {
        return Math.min(maxStructureY(bedY), Math.max(minStructureY(bedY), y));
    }

    /**
     * True if this single column is real-Nether core — the gate that keeps Nether structures out of the
     * ordinary overworld and off the mountain crossfade.
     *
     * <p>The End band wins any overlap, exactly as the core fill yields those columns. No separate
     * "is the End band on" flag is needed: a disabled phase collapses to zero length in
     * {@link WorldGenCycle#fromConfig}, so its ramp is already 0 everywhere.</p>
     */
    public static boolean isCoreColumn(WorldGenCycle cycle, long seed, int worldX, int worldZ) {
        if (cycle == null) return false;
        int wavedX = NetherMountainTerrain.wavyX(seed, worldX, worldZ);
        return cycle.isNetherCore(wavedX) && cycle.endMiddleRamp(wavedX) <= 0.0;
    }

    /**
     * True if the whole X span {@code [minX, maxX]} is core, plus a wave margin on each side so the
     * undulating front cannot cut a corner off the footprint. Structures spill well past the chunk they are
     * sited from, so this — not {@link #isCoreColumn} — is what a footprint should ask: it is what stops
     * half a fortress emerging from the netherrack into a snowy mountainside.
     *
     * <p>Only X is scanned, and the margin is why: the band is a contiguous X span whose edge waves by at
     * most {@link NetherMountainTerrain#maxEdgeShift()}, so a span that is core a margin past both ends is
     * core at every Z inside it. This is the same test {@code NetherTransitionFeature.isFullCoreChunk} runs
     * before it decorates a chunk as real Nether.</p>
     */
    public static boolean isCoreFootprint(WorldGenCycle cycle, int minX, int maxX) {
        if (cycle == null) return false;
        int margin = NetherMountainTerrain.maxEdgeShift();
        for (int worldX = minX - margin; worldX <= maxX + margin; worldX++) {
            if (!cycle.isNetherCore(worldX)) return false;
            if (cycle.endMiddleRamp(worldX) > 0.0) return false;   // the End band wins any overlap
        }
        return true;
    }
}
