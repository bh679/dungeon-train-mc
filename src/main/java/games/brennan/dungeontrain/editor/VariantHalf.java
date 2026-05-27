package games.brennan.dungeontrain.editor;

/**
 * Per-{@link VariantState} half/orientation config for blocks that expose
 * {@code SLAB_TYPE} or {@code HALF} (slabs, stairs, trapdoors). Independent of
 * the facing {@link VariantRotation} so stairs can lock facing while randomly
 * flipping their half (or vice versa).
 *
 * <p>Three modes:
 * <ul>
 *   <li>{@link Mode#TOP} — force top half.</li>
 *   <li>{@link Mode#RANDOM} — roll TOP/BOTTOM at spawn time via the existing
 *       flip seed in {@link RotationApplier}.</li>
 *   <li>{@link Mode#BOTTOM} — force bottom half.</li>
 * </ul>
 *
 * <p>{@link Mode#RANDOM} is the default — it matches the historical
 * {@code randomizeFlip} behavior for variants without an explicit half
 * choice. {@code DOUBLE} slabs aren't representable here; authors who need a
 * stable DOUBLE slab capture it as a separate variant with no half override
 * (the canonical constructor preserves whatever the captured state encodes
 * when LOCK migration is applied at read time).
 */
public record VariantHalf(Mode mode) {

    public enum Mode { TOP, RANDOM, BOTTOM }

    /** Default: random flip, matching the historical {@code randomizeFlip} behavior. */
    public static final VariantHalf NONE = new VariantHalf(Mode.RANDOM);

    public VariantHalf {
        if (mode == null) mode = Mode.RANDOM;
    }

    /** True when this is the no-op default — used to skip JSON emission. */
    public boolean isDefault() {
        return mode == Mode.RANDOM;
    }

    public static VariantHalf top() {
        return new VariantHalf(Mode.TOP);
    }

    public static VariantHalf bottom() {
        return new VariantHalf(Mode.BOTTOM);
    }
}
