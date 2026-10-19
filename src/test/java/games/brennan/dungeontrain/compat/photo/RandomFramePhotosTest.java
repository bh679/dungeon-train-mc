package games.brennan.dungeontrain.compat.photo;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RandomFramePhotosTest {

    @Test
    void sameCellSameSeedPicksTheSamePhoto() {
        assertEquals(RandomFramePhotos.seedFor(42L, 7, new BlockPos(1, 2, 3)),
            RandomFramePhotos.seedFor(42L, 7, new BlockPos(1, 2, 3)));
    }

    @Test
    void neighbouringFramesAndCarriagesDiffer() {
        long base = RandomFramePhotos.seedFor(42L, 7, new BlockPos(1, 2, 3));
        assertNotEquals(base, RandomFramePhotos.seedFor(42L, 7, new BlockPos(2, 2, 3)));
        assertNotEquals(base, RandomFramePhotos.seedFor(42L, 8, new BlockPos(1, 2, 3)));
        assertNotEquals(base, RandomFramePhotos.seedFor(43L, 7, new BlockPos(1, 2, 3)));
    }

    @Test
    void testPhotoHasSkyAboveAndHillsBelow() {
        assertNotEquals(TestFramePhoto.colourAt(5, 2), TestFramePhoto.colourAt(5, 62));
    }
}
