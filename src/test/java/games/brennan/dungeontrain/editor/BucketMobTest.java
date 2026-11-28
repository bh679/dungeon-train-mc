package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.animal.TropicalFish;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A bucket of fish is a mob to block variants ({@link BucketMob}, {@link VariantFish}). */
final class BucketMobTest {

    private static final ResourceLocation FISH = ResourceLocation.parse("minecraft:tropical_fish");
    private static final ResourceLocation COD = ResourceLocation.parse("minecraft:cod");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("fish buckets are mob buckets, plain buckets are not")
    void mobBuckets() {
        assertTrue(BucketMob.isMobBucket(new ItemStack(Items.TROPICAL_FISH_BUCKET)));
        assertTrue(BucketMob.isMobBucket(new ItemStack(Items.AXOLOTL_BUCKET)));
        assertFalse(BucketMob.isMobBucket(new ItemStack(Items.WATER_BUCKET)));
        assertFalse(BucketMob.isMobBucket(new ItemStack(Items.BUCKET)));
        assertFalse(BucketMob.isMobBucket(ItemStack.EMPTY));
    }

    @Test
    @DisplayName("a fish bucket is no longer read as water")
    void notALiquid() {
        assertNull(VariantLiquids.sourceStateFrom(new ItemStack(Items.TROPICAL_FISH_BUCKET)));
        assertNull(VariantLiquids.sourceStateFrom(new ItemStack(Items.COD_BUCKET)));
        assertNotNull(VariantLiquids.sourceStateFrom(new ItemStack(Items.WATER_BUCKET)));
        assertNotNull(VariantLiquids.sourceStateFrom(new ItemStack(Items.LAVA_BUCKET)));
    }

    @Test
    @DisplayName("the bucket's pattern becomes the entity's Variant; health is dropped")
    void bucketNbt() {
        CompoundTag bucket = new CompoundTag();
        bucket.putInt(TropicalFish.BUCKET_VARIANT_TAG, 117506305);
        bucket.putFloat("Health", 1.5f);
        bucket.putString("CustomName", "\"Nemo\"");
        CompoundTag out = BucketMob.mobNbt(bucket);
        assertNotNull(out);
        assertEquals(117506305, out.getInt("Variant"));
        assertFalse(out.contains(TropicalFish.BUCKET_VARIANT_TAG));
        assertFalse(out.contains("Health"));
        assertEquals("\"Nemo\"", out.getString("CustomName"));
        assertTrue(bucket.contains("Health"), "input not mutated");
    }

    @Test
    @DisplayName("an empty or health-only bucket carries no NBT")
    void emptyBucketNbt() {
        assertNull(BucketMob.mobNbt(null));
        assertNull(BucketMob.mobNbt(new CompoundTag()));
        CompoundTag healthOnly = new CompoundTag();
        healthOnly.putFloat("Health", 3f);
        assertNull(BucketMob.mobNbt(healthOnly));
    }

    @Test
    @DisplayName("an unpatterned tropical fish gets a common pattern, stable per seed")
    void fishPattern() {
        CompoundTag a = VariantFish.withPattern(FISH, null, 42L);
        CompoundTag b = VariantFish.withPattern(FISH, null, 42L);
        assertNotNull(a);
        assertEquals(a.getInt("Variant"), b.getInt("Variant"));
        int packed = a.getInt("Variant");
        assertTrue(TropicalFish.COMMON_VARIANTS.stream().anyMatch(v -> v.getPackedId() == packed));
        long distinct = java.util.stream.LongStream.range(0, 200)
            .map(s -> VariantFish.commonPattern(s)).distinct().count();
        assertTrue(distinct > 10, "seeds spread over the common patterns, got " + distinct);
        assertNotEquals(0, distinct);
    }

    @Test
    @DisplayName("an authored pattern and other mobs are left alone")
    void patternKept() {
        CompoundTag authored = new CompoundTag();
        authored.putInt("Variant", 7);
        assertSame(authored, VariantFish.withPattern(FISH, authored, 1L));
        assertNull(VariantFish.withPattern(COD, null, 1L));
        assertNull(VariantFish.withPattern(null, null, 1L));
    }
}
