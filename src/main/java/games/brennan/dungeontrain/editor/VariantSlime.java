package games.brennan.dungeontrain.editor;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-entry <b>slime options</b> for a slime-type mob row: which sizes it may spawn at and, for
 * BetterEnd's End slime, which of its four types. One int mask:
 *
 * <ul>
 *   <li>bits 0–3 — sizes 1–4 ({@link #SIZES});</li>
 *   <li>bits 4–7 — End slime types ({@link #TYPES}, BetterEnd's {@code Variant} 0–3).</li>
 * </ul>
 *
 * <p>Lit bits are the pool the spawn picks from at random; a part with nothing lit means every
 * value (sizes 1–4, all four End slime types). So {@link #NONE} is the default, omitted from JSON,
 * and leaves an older template spawning as it did. An egg that bakes {@code Size} / {@code Variant}
 * into its NBT still wins ({@code CarriageContentsPlacer#spawnVariantMobEntity}).</p>
 *
 * <p>Sidecar: {@code "slime": {"sizes": [2, 4], "types": ["mossy"]}}; NBT / wire: the int mask.</p>
 */
public record VariantSlime(int mask) {

    /** Sizes on offer, in button order. */
    public static final int SIZES = 4;
    /** End slime types on offer, in button order and BetterEnd {@code Variant} order. */
    public static final List<String> TYPES = List.of("chorus", "mossy", "lake", "amber");

    private static final int SIZE_MASK = (1 << SIZES) - 1;
    private static final int TYPE_SHIFT = SIZES;
    private static final int TYPE_MASK = ((1 << 4) - 1) << TYPE_SHIFT;

    /** Default: any size, any End slime type. */
    public static final VariantSlime NONE = new VariantSlime(0);

    public static final String ID_SLIME = "minecraft:slime";
    public static final String ID_MAGMA_CUBE = "minecraft:magma_cube";
    public static final String ID_END_SLIME = "betterend:end_slime";

    public VariantSlime {
        mask &= SIZE_MASK | TYPE_MASK;
    }

    /** True for a mob id whose row shows the Size option. */
    public static boolean isSlime(@Nullable ResourceLocation entityId) {
        return entityId != null && isSlime(entityId.toString());
    }

    public static boolean isSlime(@Nullable String entityId) {
        return ID_SLIME.equals(entityId) || ID_MAGMA_CUBE.equals(entityId) || ID_END_SLIME.equals(entityId);
    }

    /** True for BetterEnd's End slime — the one row that also shows the Type option. */
    public static boolean isEndSlime(@Nullable ResourceLocation entityId) {
        return entityId != null && isEndSlime(entityId.toString());
    }

    public static boolean isEndSlime(@Nullable String entityId) {
        return ID_END_SLIME.equals(entityId);
    }

    public boolean isDefault() {
        return mask == 0;
    }

    /** Size {@code size} (1–4) is lit. */
    public boolean hasSize(int size) {
        return size >= 1 && size <= SIZES && (mask & (1 << (size - 1))) != 0;
    }

    /** End slime type {@code type} (0–3) is lit. */
    public boolean hasType(int type) {
        return type >= 0 && type < TYPES.size() && (mask & (1 << (TYPE_SHIFT + type))) != 0;
    }

    public VariantSlime toggledSize(int size) {
        if (size < 1 || size > SIZES) return this;
        return new VariantSlime(mask ^ (1 << (size - 1)));
    }

    public VariantSlime toggledType(int type) {
        if (type < 0 || type >= TYPES.size()) return this;
        return new VariantSlime(mask ^ (1 << (TYPE_SHIFT + type)));
    }

    /** A copy with the End slime types cleared — what a slime or magma cube row keeps. */
    public VariantSlime withoutTypes() {
        return new VariantSlime(mask & SIZE_MASK);
    }

    /** The lit sizes, smallest first; every size when none is lit. */
    public List<Integer> sizes() {
        List<Integer> out = new ArrayList<>(SIZES);
        boolean any = (mask & SIZE_MASK) != 0;
        for (int s = 1; s <= SIZES; s++) {
            if (!any || hasSize(s)) out.add(s);
        }
        return out;
    }

    /** The lit End slime types; every type when none is lit. */
    public List<Integer> types() {
        List<Integer> out = new ArrayList<>(TYPES.size());
        boolean any = (mask & TYPE_MASK) != 0;
        for (int t = 0; t < TYPES.size(); t++) {
            if (!any || hasType(t)) out.add(t);
        }
        return out;
    }

    /** True when at least one size is lit (the sizes part is not "any"). */
    public boolean sizesSet() {
        return (mask & SIZE_MASK) != 0;
    }

    /** True when at least one End slime type is lit. */
    public boolean typesSet() {
        return (mask & TYPE_MASK) != 0;
    }

    /** One of the allowed sizes (1–4), picked at random. */
    public int rollSize(RandomSource random) {
        List<Integer> pool = sizes();
        return pool.get(random.nextInt(pool.size()));
    }

    /** One of the allowed End slime types (BetterEnd {@code Variant}), picked at random. */
    public int rollType(RandomSource random) {
        List<Integer> pool = types();
        return pool.get(random.nextInt(pool.size()));
    }

    /** Sidecar names of the lit types; empty when none is lit. */
    public List<String> typeNames() {
        List<String> out = new ArrayList<>();
        for (int t = 0; t < TYPES.size(); t++) {
            if (hasType(t)) out.add(TYPES.get(t));
        }
        return out;
    }

    /** Lit sizes only (empty when none is lit) — the sidecar's {@code sizes}. */
    public List<Integer> litSizes() {
        List<Integer> out = new ArrayList<>();
        for (int s = 1; s <= SIZES; s++) {
            if (hasSize(s)) out.add(s);
        }
        return out;
    }

    /**
     * Build from sidecar lists; {@code null} when a size is out of range or a type name unknown.
     * Either list may be null / empty (that part stays "any").
     */
    @Nullable
    public static VariantSlime fromLists(@Nullable List<Integer> sizes, @Nullable List<String> types) {
        int m = 0;
        if (sizes != null) {
            for (Integer s : sizes) {
                if (s == null || s < 1 || s > SIZES) return null;
                m |= 1 << (s - 1);
            }
        }
        if (types != null) {
            for (String n : types) {
                int t = n == null ? -1 : TYPES.indexOf(n.trim().toLowerCase(java.util.Locale.ROOT));
                if (t < 0) return null;
                m |= 1 << (TYPE_SHIFT + t);
            }
        }
        return new VariantSlime(m);
    }
}
