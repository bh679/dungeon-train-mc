package games.brennan.dungeontrain.worldgen.legacy.noise;

/**
 * The old (Alpha/Beta-era) math helpers the legacy generators depend on bit-for-bit: the 65536-entry
 * float sine table indexed with the {@code 10430.38F} float constant (modern {@code Mth} indexes with a
 * double, which drifts the table index on large angles), and the truncating floor.
 */
public final class LegacyMath {

    private static final float[] SIN_TABLE = new float[65536];

    static {
        for (int i = 0; i < SIN_TABLE.length; i++) {
            SIN_TABLE[i] = (float) Math.sin((double) i * Math.PI * 2.0D / 65536.0D);
        }
    }

    private LegacyMath() {}

    public static float sin(float f) {
        return SIN_TABLE[(int) (f * 10430.38F) & 0xFFFF];
    }

    public static float cos(float f) {
        return SIN_TABLE[(int) (f * 10430.38F + 16384.0F) & 0xFFFF];
    }

    /** {@code (int) d}, stepped down for negative non-integers. Saturates at the int range like the original. */
    public static int floor(double d) {
        int i = (int) d;
        return d < (double) i ? i - 1 : i;
    }
}
