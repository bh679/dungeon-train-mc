package games.brennan.dungeontrain.worldgen.density;

import games.brennan.dungeontrain.worldgen.NetherMountainTerrain;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;

/**
 * The pure per-quart <b>band biome decision</b> behind {@code MultiNoiseBiomeSourceMixin} — which of
 * DT's forced biomes (if any) replaces the vanilla pick at a column. Extracted from the mixin body so
 * it is unit-testable without a NeoForge bootstrap (same convention as {@link WorldGenCycle} /
 * {@code NetherBandCoreGateTest}); the mixin is a thin shell that maps the result onto the live
 * biome providers.
 *
 * <p>Decision order (byte-identical to the pre-extraction mixin):</p>
 * <ol>
 *   <li>below sea level → {@link Result#ORIGINAL} (natural cave biomes);</li>
 *   <li>Nether-band core at the edge-waved X → {@link Result#NETHER_CORE};</li>
 *   <li>End-band core at the un-waved X → {@link Result#END_CORE};</li>
 *   <li>a raising mountain column at the waved X → {@link Result#HIGHLAND};</li>
 *   <li>otherwise → {@link Result#ORIGINAL}.</li>
 * </ol>
 *
 * <p>Hot early-out: before any {@link NetherMountainTerrain#wavyX} (multi-octave noise per quart),
 * the O(1) {@link WorldGenCycle#netherInfluence}/{@link WorldGenCycle#endSegmentInfluence} window
 * checks prove the column is plain overworld for the ride's off-band majority. Conservative — the
 * waved X can wander at most {@link NetherMountainTerrain#maxEdgeShift()} blocks, exactly the margin
 * passed — so a skipped column is provably one every branch below would have declined.</p>
 */
public final class BandBiomeDecision {

    /** Which forced biome (if any) the column gets; the mixin maps this onto the live providers. */
    public enum Result { ORIGINAL, NETHER_CORE, END_CORE, HIGHLAND }

    private BandBiomeDecision() {}

    /**
     * Decide the forced biome for the quart at block coords {@code (blockX, blockY, blockZ)}.
     * {@code hasNetherCore}/{@code hasEndCore} mirror the mixin's provider-null guards — a missing
     * provider disables that branch (never a fall-through to a null lookup). Pure: depends only on
     * the cycle layout + seed, so a dense grid test can pin it against a reimplemented reference.
     */
    public static Result decide(WorldGenCycle cycle, long seed, int seaLevel,
                                boolean hasNetherCore, boolean hasEndCore,
                                int blockX, int blockY, int blockZ) {
        if (cycle == null) return Result.ORIGINAL;
        if (blockY < seaLevel) return Result.ORIGINAL;                // natural cave biomes below sea
        // Off-band early-out — no waved or un-waved lookup at this X can land in either band.
        if (!cycle.netherInfluence(blockX, NetherMountainTerrain.maxEdgeShift())
                && !cycle.endSegmentInfluence(blockX)) {
            return Result.ORIGINAL;
        }
        // Match the density router's edge-waved front so the forced-biome boundary undulates with the
        // terrain (the whole band — core included — is evaluated at the same waved X).
        int wx = NetherMountainTerrain.wavyX(seed, blockX, blockZ);
        // Real-Nether core columns sample ALL five real Nether biomes the way the Nether does (checked
        // before the highland pick — the core is also a "raising" mountain column).
        if (hasNetherCore && cycle.isNetherCore(wx)) return Result.NETHER_CORE;
        // End-core columns: un-waved block X (no mountain edge to match — the End band isn't a
        // NetherMountainTerrain feature).
        if (hasEndCore && cycle.isEndCore(blockX)) return Result.END_CORE;
        if (!NetherMountainTerrain.raises(cycle, wx)) return Result.ORIGINAL; // not a band mountain column
        return Result.HIGHLAND;
    }
}
