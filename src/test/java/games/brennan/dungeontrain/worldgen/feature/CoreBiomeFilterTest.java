package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Coverage for {@link CoreBiomeFilter} — the Nether core's per-column biome gate that keeps a mixed
 * chunk's decoration in the columns whose biome lists it (brimstone buds stay in erupting_inferno).
 */
final class CoreBiomeFilterTest {

    @Test
    @DisplayName("keeps a position only where the column test allows it; uses X/Z, ignores Y")
    void gatesByColumn() {
        CoreBiomeFilter filter = new CoreBiomeFilter((x, z) -> x < 8); // left half of the chunk "lists" the feature
        assertEquals(1, filter.getPositions(null, null, new BlockPos(3, 90, 5)).count());
        assertEquals(1, filter.getPositions(null, null, new BlockPos(3, -40, 15)).count());
        assertEquals(0, filter.getPositions(null, null, new BlockPos(8, 90, 5)).count());
        assertEquals(0, filter.getPositions(null, null, new BlockPos(15, 90, 0)).count());
    }

    @Test
    @DisplayName("ColumnBiomes samples each column once, keyed by both X and Z (negatives included)")
    void memoisesPerColumn() {
        AtomicInteger calls = new AtomicInteger();
        CoreBiomeFilter.ColumnBiomes biomes = new CoreBiomeFilter.ColumnBiomes((x, z) -> {
            calls.incrementAndGet();
            return null;
        });
        for (int i = 0; i < 3; i++) {
            assertNull(biomes.at(4640, 46));
            assertNull(biomes.at(4640, -46));
            assertNull(biomes.at(-4640, 46));
            assertNull(biomes.at(46, 4640));
        }
        assertEquals(4, calls.get());
    }
}
