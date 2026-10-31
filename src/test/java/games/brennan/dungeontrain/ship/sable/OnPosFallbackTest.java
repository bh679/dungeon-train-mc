package games.brennan.dungeontrain.ship.sable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnPosFallbackTest {

    @Test
    void unsupportedIsTheBlockBelowTheFeet() {
        assertEquals(new BlockPos(10, 63, -4), OnPosFallback.unsupported(10.7, 64.0, -3.2, 0.5000001F));
    }

    @Test
    void unsupportedFloorsNegativeCoordinates() {
        assertEquals(new BlockPos(-1, -1, -1), OnPosFallback.unsupported(-0.5, 0.0, -0.01, 0.2F));
    }

    @Test
    void zeroOffsetIsTheFeetBlock() {
        assertEquals(new BlockPos(3, 70, 3), OnPosFallback.unsupported(3.5, 70.0, 3.5, 0.0F));
    }

    @Test
    void plotPositionAsksForItsOwnChunk() {
        BlockPos plot = new BlockPos(20_481_003, 80, -17);
        long expected = ChunkPos.asLong(20_481_003 >> 4, -2);
        assertTrue(OnPosFallback.chunkLoadedFor(plot, key -> key == expected));
        assertFalse(OnPosFallback.chunkLoadedFor(plot, key -> key == ChunkPos.asLong(0, 0)));
    }
}
