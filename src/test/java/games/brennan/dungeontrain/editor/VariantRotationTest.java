package games.brennan.dungeontrain.editor;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link VariantRotation}'s canonical-constructor
 * invariants and bitmask helpers. No Forge / Minecraft bootstrap required —
 * these run in plain JUnit alongside {@link CarriageVariantBlocksTest}.
 */
final class VariantRotationTest {

    @Test
    @DisplayName("NONE is RANDOM with empty mask, isDefault=true")
    void none_isDefault() {
        assertEquals(VariantRotation.Mode.RANDOM, VariantRotation.NONE.mode());
        assertEquals(0, VariantRotation.NONE.dirMask());
        assertTrue(VariantRotation.NONE.isDefault());
    }

    @Test
    @DisplayName("RANDOM with non-zero mask gets clamped to mask=0")
    void random_clampsMask() {
        VariantRotation r = new VariantRotation(VariantRotation.Mode.RANDOM, 0b101010);
        assertEquals(0, r.dirMask());
        assertTrue(r.isDefault());
    }

    @Test
    @DisplayName("LOCK with multi-bit mask collapses to lowest bit")
    void lock_collapsesToLowestBit() {
        // EAST(5), WEST(4), UP(1) all set → keeps DOWN/UP slot only? No,
        // lowestOneBit picks the lowest set bit. UP=1 is the lowest, so result is bit 1 only.
        int multi = (1 << Direction.UP.ordinal())
            | (1 << Direction.WEST.ordinal())
            | (1 << Direction.EAST.ordinal());
        VariantRotation r = new VariantRotation(VariantRotation.Mode.LOCK, multi);
        assertEquals(1 << Direction.UP.ordinal(), r.dirMask());
        assertEquals(VariantRotation.Mode.LOCK, r.mode());
        assertFalse(r.isDefault());
    }

    @Test
    @DisplayName("LOCK with empty mask falls back to NONE-shape (mode=RANDOM, mask=0)")
    void lock_emptyMaskFallsBack() {
        VariantRotation r = new VariantRotation(VariantRotation.Mode.LOCK, 0);
        assertEquals(VariantRotation.Mode.RANDOM, r.mode());
        assertEquals(0, r.dirMask());
        assertTrue(r.isDefault());
    }

    @Test
    @DisplayName("OPTIONS with empty mask falls back to NONE-shape")
    void options_emptyMaskFallsBack() {
        VariantRotation r = new VariantRotation(VariantRotation.Mode.OPTIONS, 0);
        assertEquals(VariantRotation.Mode.RANDOM, r.mode());
        assertEquals(0, r.dirMask());
        assertTrue(r.isDefault());
    }

    @Test
    @DisplayName("OPTIONS preserves a multi-bit mask")
    void options_preservesMultiBit() {
        int mask = (1 << Direction.UP.ordinal())
            | (1 << Direction.WEST.ordinal())
            | (1 << Direction.EAST.ordinal());
        VariantRotation r = new VariantRotation(VariantRotation.Mode.OPTIONS, mask);
        assertEquals(VariantRotation.Mode.OPTIONS, r.mode());
        assertEquals(mask, r.dirMask());
        assertFalse(r.isDefault());
    }

    @Test
    @DisplayName("dirMask higher bits past 6 are masked off")
    void mask_clampsToSixBits() {
        // Bit 6+ should be discarded since only Direction's 6 ordinals are valid.
        VariantRotation r = new VariantRotation(VariantRotation.Mode.OPTIONS, 0xFF);
        assertEquals(0x3F, r.dirMask());
    }

    @Test
    @DisplayName("null mode normalises to RANDOM")
    void nullMode_normalises() {
        VariantRotation r = new VariantRotation(null, 1 << Direction.EAST.ordinal());
        assertEquals(VariantRotation.Mode.RANDOM, r.mode());
        // RANDOM forces dirMask=0.
        assertEquals(0, r.dirMask());
    }

    @Test
    @DisplayName("directions() reconstructs the right Direction set from the mask")
    void directions_roundTrip() {
        EnumSet<Direction> picked = EnumSet.of(Direction.UP, Direction.EAST, Direction.NORTH);
        int mask = 0;
        for (Direction d : picked) mask |= 1 << d.ordinal();
        VariantRotation r = new VariantRotation(VariantRotation.Mode.OPTIONS, mask);
        assertEquals(picked, r.directions());
    }

    @Test
    @DisplayName("maskOf builds the same mask as manual ordinal-shift")
    void maskOf_matchesManual() {
        for (Direction d : Direction.values()) {
            assertEquals(1 << d.ordinal(), VariantRotation.maskOf(d));
        }
    }

    @Test
    @DisplayName("maskOf(Iterable) matches OR of single-direction masks")
    void maskOf_iterable() {
        EnumSet<Direction> set = EnumSet.of(Direction.DOWN, Direction.SOUTH);
        int expected = (1 << Direction.DOWN.ordinal()) | (1 << Direction.SOUTH.ordinal());
        assertEquals(expected, VariantRotation.maskOf(set));
    }

    @Test
    @DisplayName("lock(Direction) factory yields a LOCK with single-bit mask")
    void lockFactory() {
        VariantRotation r = VariantRotation.lock(Direction.WEST);
        assertEquals(VariantRotation.Mode.LOCK, r.mode());
        assertEquals(1 << Direction.WEST.ordinal(), r.dirMask());
        assertEquals(EnumSet.of(Direction.WEST), r.directions());
    }

    @Test
    @DisplayName("equals / hashCode by value")
    void equalsByValue() {
        VariantRotation a = new VariantRotation(VariantRotation.Mode.LOCK,
            VariantRotation.maskOf(Direction.EAST));
        VariantRotation b = new VariantRotation(VariantRotation.Mode.LOCK,
            VariantRotation.maskOf(Direction.EAST));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        VariantRotation c = new VariantRotation(VariantRotation.Mode.LOCK,
            VariantRotation.maskOf(Direction.WEST));
        assertNotEquals(a, c);
    }
}
