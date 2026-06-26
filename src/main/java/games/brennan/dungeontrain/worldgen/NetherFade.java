package games.brennan.dungeontrain.worldgen;

import net.minecraft.server.level.ServerLevel;

/**
 * Block-level Overworld↔Nether dither for the Nether transition crossfade. Where the band's
 * {@link NetherBand#netherRampAt netherRamp} climbs 0→1 (the {@code coreFade} crossfade), a world
 * feature with both an Overworld and a Nether-dark variant of identical geometry — tunnel sections
 * ({@code default}/{@code darktunnel}), tunnel portals ({@code default}/{@code darkportal}), track
 * tiles ({@code default}/…/{@code nethertracks}) — is composited <b>per block</b>: each cell takes
 * the Nether variant's block iff {@link #selectsNether} is true, else the Overworld variant's. The
 * dither mirrors the terrain's stone→netherrack recolour ({@code NetherTransitionFeature}'s
 * crossfade), so the structures turn Nether in the same clumps the ground does.
 *
 * <p>The decision is a pure function of {@code (generationSeed, x, y, z, ramp)} — using the same
 * {@link Disintegration#coherentNoise} (and per-world {@code generationSeed}) the terrain uses — so
 * it is deterministic and reproducible across reloads and rolling-window re-renders, exactly like
 * the rest of the worldgen.</p>
 */
public final class NetherFade {

    private NetherFade() {}

    /**
     * Salt for the structure dither — a distinct stream from {@code NetherTransitionFeature}'s
     * {@code CROSSFADE_DITHER_SALT}, but fed through the same {@link Disintegration#coherentNoise}
     * (cells 8/3), so the structure fade clumps at the same scale and organic character as the
     * ground's stone→netherrack crossfade.
     */
    public static final long SALT = 0x4F1BBCDCBFA53E0BL;

    /**
     * Whether the block at world {@code (x, y, z)} should take the <b>Nether-dark</b> variant rather
     * than the Overworld one, given the column's {@code netherRamp}. {@code ramp <= 0} (pure
     * overworld approach) is never Nether; {@code ramp >= 1} (real-Nether core) is always Nether;
     * in between it is a coherent-noise dither ({@code coherentNoise < ramp}) that matches the
     * terrain's stone→netherrack recolour cell-for-cell in character. Pure / deterministic.
     */
    public static boolean selectsNether(long genSeed, int x, int y, int z, double ramp) {
        if (ramp <= 0.0) return false;
        if (ramp >= 1.0) return true;
        return Disintegration.coherentNoise(genSeed ^ SALT, x, y, z) < ramp;
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
