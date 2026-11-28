package games.brennan.dungeontrain.editor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.TropicalFish;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Colours for tropical fish rows. The variant spawn skips {@code finalizeSpawn}, where vanilla rolls
 * a fish's pattern, so a row with no {@code Variant} spawned as the default white kob every time.
 * Such a row now gets one of vanilla's common patterns, picked from the spawn seed and position so
 * the same carriage shows the same fish.
 */
public final class VariantFish {

    /** The entity save key holding a tropical fish's packed pattern + colours. */
    static final String VARIANT_KEY = "Variant";

    private static final String ID_TROPICAL_FISH = "minecraft:tropical_fish";

    private VariantFish() {}

    /** True for vanilla's tropical fish. */
    public static boolean isTropicalFish(@Nullable ResourceLocation entityId) {
        return entityId != null && ID_TROPICAL_FISH.equals(entityId.toString());
    }

    /**
     * {@code mobNbt} with a common pattern added when {@code entityId} is a tropical fish without
     * one; otherwise {@code mobNbt} unchanged. Never mutates {@code mobNbt}.
     */
    public static @Nullable CompoundTag withPattern(@Nullable ResourceLocation entityId,
                                                    @Nullable CompoundTag mobNbt, long seed) {
        if (!isTropicalFish(entityId)) return mobNbt;
        if (mobNbt != null && mobNbt.contains(VARIANT_KEY)) return mobNbt;
        CompoundTag out = mobNbt == null ? new CompoundTag() : mobNbt.copy();
        out.putInt(VARIANT_KEY, commonPattern(seed));
        return out;
    }

    /** One of vanilla's common packed patterns, chosen by {@code seed}. */
    static int commonPattern(long seed) {
        List<TropicalFish.Variant> common = TropicalFish.COMMON_VARIANTS;
        int idx = (int) Math.floorMod(mix(seed), (long) common.size());
        return common.get(idx).getPackedId();
    }

    /** SplitMix64 finaliser, so neighbouring seeds land on unrelated patterns. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
