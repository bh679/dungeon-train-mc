package games.brennan.dungeontrain.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the room a heart's Creaking may spawn in: the stamped interior survives the heart's NBT
 * relative to the heart, and a spot counts only when the whole Creaking fits below the roof.
 */
final class CreakingHeartBoundsTest {

    @Test
    void interiorIsRecordedRelativeToTheHeart() {
        BlockPos heart = new BlockPos(113, -92, 641);
        BoundingBox rel = CreakingHeartBounds.relative(heart, new BlockPos(110, -93, 641), new Vec3i(7, 5, 5));
        assertEquals(new BoundingBox(-3, -1, 0, 3, 3, 4), rel);
        assertEquals(new BoundingBox(110, -93, 641, 116, -89, 645), rel.moved(heart.getX(), heart.getY(), heart.getZ()));
    }

    @Test
    void roundTripsThroughNbtAndIsAbsentWhenUnknown() {
        CompoundTag tag = new CompoundTag();
        assertNull(CreakingHeartBounds.load(tag));
        BoundingBox box = new BoundingBox(-3, -1, 0, 3, 3, 4);
        CreakingHeartBounds.save(tag, box);
        assertEquals(box, CreakingHeartBounds.load(tag));
    }

    @Test
    void spotMustLeaveHeadRoomBelowTheRoof() {
        BoundingBox room = new BoundingBox(110, -93, 641, 116, -89, 645);
        assertTrue(CreakingHeartSpawnSpot.fits(room, new BlockPos(112, -93, 642)), "on the floor");
        assertFalse(CreakingHeartSpawnSpot.fits(room, new BlockPos(112, -90, 642)), "head through the roof");
        assertFalse(CreakingHeartSpawnSpot.fits(room, new BlockPos(112, -93, 646)), "outside the side wall");
        assertFalse(CreakingHeartSpawnSpot.fits(room, new BlockPos(112, -87, 642)), "on the roof");
        assertTrue(CreakingHeartSpawnSpot.fits(null, new BlockPos(0, 0, 0)), "no room known: anywhere");
    }
}
