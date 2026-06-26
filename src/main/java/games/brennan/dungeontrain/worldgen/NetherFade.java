package games.brennan.dungeontrain.worldgen;

import net.minecraft.server.level.ServerLevel;

/**
 * Block-level Overworld↔Nether dither for the Nether transition crossfade. Where the band's
 * {@link NetherBand#netherRampAt netherRamp} climbs 0→1 (the {@code coreFade} crossfade), a world
 * feature with both an Overworld and a Nether-dark variant of identical geometry — tunnel sections
 * ({@code default}/{@code darktunnel}), tunnel portals ({@code default}/{@code darkportal}), track
 * tiles ({@code default}/…/{@code nethertracks}) — is composited <b>per block</b>: each cell takes
 * the Nether variant's block iff {@link #selectsNether} is true, else the Overworld variant's.
 *
 * <p>The dither is a per-block <b>white noise</b> hash — independent cell-to-cell, deliberately
 * <em>noisier and finer</em> than the terrain's coherent stone→netherrack crossfade — so the Nether
 * material peppers into the structures grain by grain (sparse far out, dense near the core) rather
 * than in coherent patches. The decision is a pure function of
 * {@code (generationSeed, x, y, z, ramp)} keyed on the per-world {@code generationSeed}, so it is
 * deterministic and reproducible across reloads and rolling-window re-renders.</p>
 */
public final class NetherFade {

    private NetherFade() {}

    /** Salt for the structure dither — a stream distinct from any terrain/erosion noise. */
    public static final long SALT = 0x4F1BBCDCBFA53E0BL;

    /**
     * Whether the block at world {@code (x, y, z)} should take the <b>Nether-dark</b> variant rather
     * than the Overworld one, given the column's {@code netherRamp}. {@code ramp <= 0} (pure
     * overworld approach) is never Nether; {@code ramp >= 1} (real-Nether core) is always Nether;
     * in between it is a per-block white-noise dither ({@code hash01 < ramp}) — so the Nether
     * fraction equals the ramp and rises smoothly toward the core. Pure / deterministic.
     */
    public static boolean selectsNether(long genSeed, int x, int y, int z, double ramp) {
        if (ramp <= 0.0) return false;
        if (ramp >= 1.0) return true;
        return hash01(genSeed ^ SALT, x, y, z) < ramp;
    }

    /**
     * Deterministic per-block white noise in {@code [0,1)} — a SplitMix64-style 3D integer hash
     * (the same finalizer {@code Disintegration} uses per lattice point, here sampled per block so
     * there is no spatial coherence). Independent for adjacent cells, which is exactly the grainy
     * "more noisy" dither this fade wants.
     */
    private static double hash01(long seed, int x, int y, int z) {
        long h = seed + 0x9E3779B97F4A7C15L;
        h ^= (long) x * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h ^= (long) y * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= (long) z * 0xD6E8FEB86659FD93L;
        h ^= (h >>> 31);
        return (h >>> 11) * 0x1.0p-53;
    }

    /** The band's Nether intensity ramp at {@code worldX} (0 outside the band). Convenience wrapper. */
    public static double rampAt(ServerLevel overworld, int worldX) {
        return NetherBand.netherRampAt(overworld, worldX);
    }

    /**
     * True if any column in the inclusive X-span {@code [xLo, xHi]} sits inside the Nether crossfade
     * ({@code 0 < ramp < 1}) — i.e. the feature stamped over that span is worth compositing rather
     * than stamping a single hard-phase variant. Spans are short (one tunnel section / track tile),
     * so a per-column scan is cheap and exact (an endpoint-only test could miss a crossfade that
     * starts mid-span). Returns false when the band is off (ramp is 0 everywhere).
     */
    public static boolean intersectsCrossfade(ServerLevel overworld, int xLo, int xHi) {
        for (int x = xLo; x <= xHi; x++) {
            double r = NetherBand.netherRampAt(overworld, x);
            if (r > 0.0 && r < 1.0) return true;
        }
        return false;
    }
}
