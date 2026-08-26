package games.brennan.dungeontrain.builder;

import java.util.Locale;

/**
 * The builder world's mirror setting: three structural axes plus the "V" variant-pool opt-in.
 *
 * <p>Kept on the <b>world</b> rather than on a template sidecar, which is the difference that makes
 * mirroring work here at all. The editor stores these per template and finds the template by asking
 * which plot you're standing in; a builder world holds exactly one build, has no plot grid, and —
 * until you name and save it — has no template on disk to write to. Writing them to whatever
 * carriage the build was <em>copied from</em> would edit someone else's shipped file.</p>
 *
 * <p>{@link BuilderSave} copies the flags onto the template it writes, so a build that was authored
 * mirrored stays mirrored when the editor later opens it.</p>
 *
 * <p>Packed into one int for storage: four booleans would be four NBT keys and four accessors for
 * one concept.</p>
 */
public record BuilderMirrorFlags(boolean x, boolean y, boolean z, boolean variants) {

    public static final BuilderMirrorFlags NONE = new BuilderMirrorFlags(false, false, false, false);

    /**
     * What a fresh build starts with: no structural axes, but the variant opt-in on.
     *
     * <p>"V" is the one a builder almost never wants off, and it's the one hidden behind Shift in
     * the pause menu — so it has to be on without being found first.</p>
     */
    public static final BuilderMirrorFlags DEFAULT = new BuilderMirrorFlags(false, false, false, true);

    private static final int BIT_X = 1;
    private static final int BIT_Y = 1 << 1;
    private static final int BIT_Z = 1 << 2;
    private static final int BIT_V = 1 << 3;

    public int pack() {
        return (x ? BIT_X : 0) | (y ? BIT_Y : 0) | (z ? BIT_Z : 0) | (variants ? BIT_V : 0);
    }

    public static BuilderMirrorFlags unpack(int mask) {
        return new BuilderMirrorFlags((mask & BIT_X) != 0, (mask & BIT_Y) != 0,
                (mask & BIT_Z) != 0, (mask & BIT_V) != 0);
    }

    /** Whether {@code axis} ("x"/"y"/"z"/"v") is currently on. Unknown axes read as off. */
    public boolean isOn(String axis) {
        return switch (normalise(axis)) {
            case "x" -> x;
            case "y" -> y;
            case "z" -> z;
            case "v" -> variants;
            default -> false;
        };
    }

    /** A copy with one axis set. An unknown axis returns this unchanged rather than throwing. */
    public BuilderMirrorFlags with(String axis, boolean on) {
        return switch (normalise(axis)) {
            case "x" -> new BuilderMirrorFlags(on, y, z, variants);
            case "y" -> new BuilderMirrorFlags(x, on, z, variants);
            case "z" -> new BuilderMirrorFlags(x, y, on, variants);
            case "v" -> new BuilderMirrorFlags(x, y, z, on);
            default -> this;
        };
    }

    /** True when any structural axis is on — the condition every mirror pass tests before working. */
    public boolean anyAxis() {
        return x || y || z;
    }

    private static String normalise(String axis) {
        return axis == null ? "" : axis.trim().toLowerCase(Locale.ROOT);
    }
}
