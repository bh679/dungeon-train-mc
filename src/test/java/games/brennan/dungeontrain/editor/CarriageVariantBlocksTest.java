package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure-logic parts of {@link CarriageVariantBlocks}:
 * the deterministic {@link CarriageVariantBlocks#pickIndex} seeded picker
 * and the {@link CarriageVariantBlocks#parsePos} / {@link CarriageVariantBlocks#formatPos}
 * position serialisation pair.
 *
 * <p>Methods that touch real {@link net.minecraft.world.level.block.state.BlockState}
 * instances (save/load round-trips, resolve() on populated maps) require a
 * Forge/MC bootstrap — they're covered by the integration harness on
 * Project #16 instead.</p>
 */
final class CarriageVariantBlocksTest {

    // ---- pickIndex determinism ----

    @Test
    @DisplayName("pickIndex: same inputs always yield the same index")
    void pick_isDeterministic() {
        BlockPos pos = new BlockPos(3, 1, 2);
        int first = CarriageVariantBlocks.pickIndex(pos, 12345L, 7, 3);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, CarriageVariantBlocks.pickIndex(pos, 12345L, 7, 3), "call " + i);
        }
    }

    @Test
    @DisplayName("pickIndex: varying carriage index samples the full range over many picks")
    void pick_variesByCarriageIndex() {
        BlockPos pos = new BlockPos(3, 1, 2);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(CarriageVariantBlocks.pickIndex(pos, 0xCAFEBABEL, i, 3));
        }
        assertEquals(3, seen.size(),
            "expected all 3 indices across 500 carriages at the same position, saw: " + seen);
    }

    @Test
    @DisplayName("pickIndex: varying position at the same carriage index samples the full range")
    void pick_variesByPosition() {
        Set<Integer> seen = new HashSet<>();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 7; y++) {
                for (int z = 0; z < 7; z++) {
                    seen.add(CarriageVariantBlocks.pickIndex(
                        new BlockPos(x, y, z), 0xFEEDFACEL, 42, 3));
                }
            }
        }
        assertEquals(3, seen.size(),
            "expected all 3 indices across positions at a single carriage index, saw: " + seen);
    }

    @Test
    @DisplayName("pickIndex: result is always in [0, size)")
    void pick_alwaysInRange() {
        for (int size = 1; size <= 8; size++) {
            for (int idx = -20; idx < 20; idx++) {
                int r = CarriageVariantBlocks.pickIndex(new BlockPos(0, 0, 0), 1L, idx, size);
                assertTrue(r >= 0 && r < size, "out of range: size=" + size + " r=" + r);
            }
        }
    }

    @Test
    @DisplayName("pickIndex: different world seeds can change the result at the same position+index")
    void pick_variesByWorldSeed() {
        BlockPos pos = new BlockPos(3, 1, 2);
        boolean anyDifferent = false;
        for (int i = 0; i < 100; i++) {
            int a = CarriageVariantBlocks.pickIndex(pos, 1L, i, 4);
            int b = CarriageVariantBlocks.pickIndex(pos, 2L, i, 4);
            if (a != b) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue(anyDifferent,
            "two seeds produced identical picks across 100 indices at the same position");
    }

    @Test
    @DisplayName("pickIndex: zero or negative size throws")
    void pick_rejectsZeroSize() {
        assertThrows(IllegalArgumentException.class,
            () -> CarriageVariantBlocks.pickIndex(new BlockPos(0, 0, 0), 1L, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> CarriageVariantBlocks.pickIndex(new BlockPos(0, 0, 0), 1L, 0, -1));
    }

    // ---- parsePos / formatPos round-trip ----

    @Test
    @DisplayName("formatPos/parsePos round-trips positive coords")
    void posRoundTrip_positive() {
        BlockPos pos = new BlockPos(3, 1, 2);
        String key = CarriageVariantBlocks.formatPos(pos);
        assertEquals("3,1,2", key);
        BlockPos parsed = CarriageVariantBlocks.parsePos(key);
        assertNotNull(parsed);
        assertEquals(pos, parsed);
    }

    @Test
    @DisplayName("formatPos/parsePos round-trips zero")
    void posRoundTrip_zero() {
        BlockPos pos = new BlockPos(0, 0, 0);
        BlockPos parsed = CarriageVariantBlocks.parsePos(CarriageVariantBlocks.formatPos(pos));
        assertNotNull(parsed);
        assertEquals(pos, parsed);
    }

    @Test
    @DisplayName("parsePos tolerates whitespace around commas")
    void parsePos_tolerant() {
        BlockPos p = CarriageVariantBlocks.parsePos("3, 1 , 2");
        assertNotNull(p);
        assertEquals(new BlockPos(3, 1, 2), p);
    }

    @Test
    @DisplayName("parsePos rejects malformed keys")
    void parsePos_rejectsMalformed() {
        assertNull(CarriageVariantBlocks.parsePos("1,2"));
        assertNull(CarriageVariantBlocks.parsePos("1,2,3,4"));
        assertNull(CarriageVariantBlocks.parsePos("abc,def,ghi"));
        assertNull(CarriageVariantBlocks.parsePos(""));
        assertNull(CarriageVariantBlocks.parsePos("1;2;3"));
    }

    @Test
    @DisplayName("empty() returns a sidecar with no entries")
    void empty_hasNoEntries() {
        CarriageVariantBlocks sidecar = CarriageVariantBlocks.empty();
        assertTrue(sidecar.isEmpty());
        assertEquals(0, sidecar.size());
        assertTrue(sidecar.entries().isEmpty());
    }

    // ---- Sanity: adjacent positions don't produce correlated picks ----

    @Test
    @DisplayName("pickIndex: adjacent positions at a single carriage don't all land on the same index")
    void pick_adjacentPositionsDiverge() {
        long worldSeed = 0xDEADBEEFL;
        int carriageIndex = 100;
        int size = 3;
        Set<Integer> seen = new HashSet<>();
        for (int x = 0; x < 9; x++) {
            seen.add(CarriageVariantBlocks.pickIndex(new BlockPos(x, 1, 2), worldSeed, carriageIndex, size));
        }
        assertNotEquals(1, seen.size(),
            "across 9 adjacent positions the picker collapsed to a single index — seeding is broken");
    }
}
