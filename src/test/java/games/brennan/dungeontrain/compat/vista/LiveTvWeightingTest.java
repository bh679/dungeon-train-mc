package games.brennan.dungeontrain.compat.vista;

import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveTvWeightingTest {

    @Test
    void liveDoublesAndNotLiveHalvesRoundingDown() {
        assertEquals(2, LiveTvWeighting.scale(1, true));
        assertEquals(0, LiveTvWeighting.scale(1, false), "a weight of 1 drops to 0 while nobody is live");
        assertEquals(6, LiveTvWeighting.scale(3, true));
        assertEquals(1, LiveTvWeighting.scale(3, false));
        assertEquals(0, LiveTvWeighting.scale(0, true));
    }

    @Test
    void nothingIsScaledWithoutAServer() {
        assertFalse(LiveTvWeighting.active());
        assertEquals(5, LiveTvWeighting.contentsWeight("livestream", 5, 3));
    }

    @Test
    void aTemplateWithATelevisionInItsPaletteCounts() {
        assertTrue(LiveTvWeighting.paletteHasTv(template("minecraft:oak_planks", "vista:television")));
        assertFalse(LiveTvWeighting.paletteHasTv(template("minecraft:oak_planks", "minecraft:bookshelf")));
    }

    @Test
    void zeroWeightIsNeverPickedAndAllZeroAsksForTheFallback() {
        BlockPos pos = new BlockPos(1, 2, 3);
        for (long seed = 0; seed < 200; seed++) {
            assertNotEquals(0, CarriageVariantBlocks.pickIndexFromWeightsAllowZero(pos, seed, 4, new int[] {0, 3, 1}));
            assertNotEquals(1, CarriageVariantBlocks.pickIndexFromLockGroupAllowZero(2, seed, 4, new int[] {5, 0}));
        }
        assertEquals(-1, CarriageVariantBlocks.pickIndexFromWeightsAllowZero(pos, 9, 4, new int[] {0, 0}));
    }

    @Test
    void withNoZeroTheAllowZeroPickersMatchTheAuthoredOnes() {
        BlockPos pos = new BlockPos(4, 1, 2);
        int[] weights = {2, 5, 1};
        for (long seed = 0; seed < 200; seed++) {
            assertEquals(CarriageVariantBlocks.pickIndexFromWeights(pos, seed, 7, weights),
                CarriageVariantBlocks.pickIndexFromWeightsAllowZero(pos, seed, 7, weights));
            assertEquals(CarriageVariantBlocks.pickIndexFromLockGroup(3, seed, 7, weights),
                CarriageVariantBlocks.pickIndexFromLockGroupAllowZero(3, seed, 7, weights));
        }
    }

    private static CompoundTag template(String... names) {
        ListTag palette = new ListTag();
        for (String name : names) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", name);
            palette.add(entry);
        }
        CompoundTag tag = new CompoundTag();
        tag.put("palette", palette);
        return tag;
    }
}
