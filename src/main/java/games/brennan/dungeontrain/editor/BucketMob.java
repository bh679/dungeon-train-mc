package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.mixin.MobBucketItemAccessor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.TropicalFish;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;

/**
 * A bucket of fish (tropical fish, cod, salmon, pufferfish, axolotl, tadpole) is a mob, not water,
 * to the block-variant system: it adds the mob row its spawn egg would, carrying the bucket's mob
 * data (a tropical fish keeps its pattern).
 *
 * <p>Without this a {@link MobBucketItem} is just a {@code BucketItem} of water, so the editor
 * stored a water source and lost the fish ({@link VariantLiquids#sourceStateFrom} now refuses it).</p>
 */
public final class BucketMob {

    /** Bucket data that must not follow the fish into every carriage it is rolled into. */
    private static final String HEALTH = "Health";

    private BucketMob() {}

    /** True when {@code held} is a bucket holding a mob. */
    public static boolean isMobBucket(@Nullable ItemStack held) {
        return held != null && !held.isEmpty() && held.getItem() instanceof MobBucketItem;
    }

    /**
     * The mob row for a held mob bucket, or {@code null} when {@code held} is not one (or its mob
     * has no registry id).
     */
    public static @Nullable VariantState from(@Nullable ItemStack held) {
        if (!isMobBucket(held)) return null;
        EntityType<?> type = ((MobBucketItemAccessor) held.getItem()).dungeontrain$getType();
        ResourceLocation eid = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (eid == null) return null;
        CustomData data = held.get(DataComponents.BUCKET_ENTITY_DATA);
        return VariantState.ofMob(eid, mobNbt(data == null ? null : data.copyTag()), 1, VariantRotation.NONE);
    }

    /**
     * Entity NBT from a bucket's mob data, or {@code null} when nothing is left. Bucket keys are the
     * entity's own save keys, except the tropical fish pattern, which the bucket stores as
     * {@code BucketVariantTag} and the entity reads as {@code Variant}. Health is dropped.
     */
    static @Nullable CompoundTag mobNbt(@Nullable CompoundTag bucketData) {
        if (bucketData == null || bucketData.isEmpty()) return null;
        CompoundTag out = bucketData.copy();
        out.remove(HEALTH);
        if (out.contains(TropicalFish.BUCKET_VARIANT_TAG)) {
            out.putInt(VariantFish.VARIANT_KEY, out.getInt(TropicalFish.BUCKET_VARIANT_TAG));
            out.remove(TropicalFish.BUCKET_VARIANT_TAG);
        }
        return out.isEmpty() ? null : out;
    }
}
