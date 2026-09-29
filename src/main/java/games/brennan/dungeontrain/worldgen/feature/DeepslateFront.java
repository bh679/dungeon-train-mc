package games.brennan.dungeontrain.worldgen.feature;

/**
 * Where the Nether band's mountain rock turns from stone to <b>deepslate</b>: the half of the approach
 * nearer the real-Nether core, behind a wavy front. Pure — no Minecraft types.
 *
 * <p>A cell is deepslate when the column's distance to the core ({@code WorldGenCycle#netherCoreGap}) is
 * under half the approach length ({@code WorldGenCycle#netherApproachLength}) plus a coherent 3D wobble
 * of ±{@link #WAVE} blocks ({@link CavernNoise#field01}, ~56-block wavelength), so the boundary snakes
 * along the band and up the column like a natural stratum rather than a flat plane. Mirrored on the
 * fall side, since the gap is symmetric about the core.</p>
 */
public final class DeepslateFront {

    /** Half-amplitude (blocks along the band) of the stone/deepslate front's wobble. */
    public static final int WAVE = 48;
    private static final long SALT = 0x27D4EB2F165667C5L;

    private DeepslateFront() {}

    /** The core-ward half of the approach: {@code approachLength / 2}. */
    public static int half(int approachLength) {
        return Math.max(0, approachLength) / 2;
    }

    /** False when no cell of this column can be deepslate (skips the column scan for the far half). */
    public static boolean mayApply(int gap, int half) {
        return gap < half + WAVE;
    }

    /** True when the cell at {@code (x, y, z)} of a column {@code gap} blocks from the core is deepslate. */
    public static boolean isDeepslate(long seed, int gap, int half, int x, int y, int z) {
        if (gap >= half + WAVE) return false;
        if (gap < half - WAVE) return true;
        double wobble = (CavernNoise.field01(seed ^ SALT, x, y, z) - 0.5) * 2.0 * WAVE;
        return gap < half + wobble;
    }
}
