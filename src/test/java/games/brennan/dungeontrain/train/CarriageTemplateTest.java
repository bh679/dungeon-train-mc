package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link CarriageTemplate}'s pure-logic functions —
 * the deterministic carriage-variant selector {@link CarriageTemplate#typeForIndex(int)},
 * the {@link CarriageType} enum contract, and the {@link CarriageDims}
 * invariants + clamp helper.
 *
 * <p>Runtime-dependent methods ({@code placeAt}, {@code eraseAt}) need a live
 * Forge/Minecraft server and are covered by the Forge GameTestServer integration
 * tests tracked separately on Project #16.</p>
 *
 * <p>These tests run in plain JUnit without a Forge/MC runtime. The class is
 * designed so {@code CarriageTemplate}'s own static init does not trigger
 * {@code Blocks.*} registry access — the {@code BlockState} fields are held
 * inside a lazy-init holder class that only loads when {@code placeAt} /
 * {@code eraseAt} are called from a live server path.</p>
 */
final class CarriageTemplateTest {

    // ---- typeForIndex: deterministic variant cycling ----

    @Test
    @DisplayName("typeForIndex(0) returns STANDARD")
    void typeForIndex_zero_returnsStandard() {
        assertEquals(CarriageType.STANDARD, CarriageTemplate.typeForIndex(0));
    }

    @Test
    @DisplayName("typeForIndex(1) returns WINDOWED")
    void typeForIndex_one_returnsWindowed() {
        assertEquals(CarriageType.WINDOWED, CarriageTemplate.typeForIndex(1));
    }

    @Test
    @DisplayName("typeForIndex(2) returns SOLID_ROOF")
    void typeForIndex_two_returnsSolidRoof() {
        assertEquals(CarriageType.SOLID_ROOF, CarriageTemplate.typeForIndex(2));
    }

    @Test
    @DisplayName("typeForIndex(3) returns FLATBED")
    void typeForIndex_three_returnsFlatbed() {
        assertEquals(CarriageType.FLATBED, CarriageTemplate.typeForIndex(3));
    }

    @Test
    @DisplayName("typeForIndex(4) wraps back to STANDARD — cycle of 4")
    void typeForIndex_four_wrapsToStandard() {
        assertEquals(CarriageType.STANDARD, CarriageTemplate.typeForIndex(4));
    }

    @Test
    @DisplayName("typeForIndex(-1) returns FLATBED — Math.floorMod(-1, 4) == 3")
    void typeForIndex_negativeOne_returnsFlatbed() {
        // Math.floorMod distinguishes from Java's % operator: -1 % 4 == -1,
        // but Math.floorMod(-1, 4) == 3. The rolling-window manager depends
        // on negative indices wrapping forward (carriage index -1 is behind
        // the spawner), so this test pins the contract explicitly.
        assertEquals(CarriageType.FLATBED, CarriageTemplate.typeForIndex(-1));
    }

    @Test
    @DisplayName("typeForIndex(-4) returns STANDARD")
    void typeForIndex_negativeFour_returnsStandard() {
        assertEquals(CarriageType.STANDARD, CarriageTemplate.typeForIndex(-4));
    }

    @Test
    @DisplayName("typeForIndex(400) returns STANDARD — large multiples of 4 still wrap")
    void typeForIndex_largeMultipleOfFour_returnsStandard() {
        assertEquals(CarriageType.STANDARD, CarriageTemplate.typeForIndex(400));
    }

    // ---- CarriageType enum contract ----

    @Test
    @DisplayName("CarriageType has exactly 4 variants in declared order")
    void carriageType_hasFourVariantsInDeclaredOrder() {
        // Order is part of the public contract: typeForIndex uses
        // CarriageType.values()[i % 4], and the wiki Features table documents
        // the STANDARD → WINDOWED → SOLID_ROOF → FLATBED cycle. Reordering the
        // enum silently changes which variant spawns at any given index, so
        // lock it here.
        assertArrayEquals(
            new CarriageType[] {
                CarriageType.STANDARD,
                CarriageType.WINDOWED,
                CarriageType.SOLID_ROOF,
                CarriageType.FLATBED,
            },
            CarriageType.values()
        );
    }

    // ---- CarriageDims: default + invariants ----

    @Test
    @DisplayName("CarriageDims.DEFAULT is 9x9x7 — the shipped footprint")
    void carriageDims_default_is9x9x7() {
        // DEFAULT drives every code path that hasn't got a WorldData dims
        // tag yet: legacy world saves (missing NBT tags fall back to
        // CarriageDims.DEFAULT), the options-screen initial field values,
        // and integration-test fixtures. Any change needs a paired wiki +
        // doc update.
        assertEquals(9, CarriageDims.DEFAULT.length(), "length");
        assertEquals(9, CarriageDims.DEFAULT.width(), "width");
        assertEquals(7, CarriageDims.DEFAULT.height(), "height");
    }

    @Test
    @DisplayName("CarriageDims constructor rejects sub-floor values")
    void carriageDims_constructor_rejectsBelowFloor() {
        assertThrows(IllegalArgumentException.class,
            () -> new CarriageDims(CarriageDims.MIN_LENGTH - 1, CarriageDims.MIN_WIDTH, CarriageDims.MIN_HEIGHT),
            "length below floor");
        assertThrows(IllegalArgumentException.class,
            () -> new CarriageDims(CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH - 1, CarriageDims.MIN_HEIGHT),
            "width below floor");
        assertThrows(IllegalArgumentException.class,
            () -> new CarriageDims(CarriageDims.MIN_LENGTH, CarriageDims.MIN_WIDTH, CarriageDims.MIN_HEIGHT - 1),
            "height below floor");
    }

    @Test
    @DisplayName("CarriageDims constructor rejects above-ceiling values")
    void carriageDims_constructor_rejectsAboveCeiling() {
        assertThrows(IllegalArgumentException.class,
            () -> new CarriageDims(CarriageDims.MAX_LENGTH + 1, CarriageDims.MAX_WIDTH, CarriageDims.MAX_HEIGHT),
            "length above ceiling");
        assertThrows(IllegalArgumentException.class,
            () -> new CarriageDims(CarriageDims.MAX_LENGTH, CarriageDims.MAX_WIDTH + 1, CarriageDims.MAX_HEIGHT),
            "width above ceiling");
        assertThrows(IllegalArgumentException.class,
            () -> new CarriageDims(CarriageDims.MAX_LENGTH, CarriageDims.MAX_WIDTH, CarriageDims.MAX_HEIGHT + 1),
            "height above ceiling");
    }

    @Test
    @DisplayName("CarriageDims.clamp snaps out-of-range values to floor and ceiling")
    void carriageDims_clamp_snapsToFloorAndCeiling() {
        // Self-heal path: NBT load + UI input both route through clamp() so
        // a saved-out-of-range value can't crash the world.
        CarriageDims belowFloor = CarriageDims.clamp(1, 1, 1);
        assertEquals(CarriageDims.MIN_LENGTH, belowFloor.length(), "clamp floor length");
        assertEquals(CarriageDims.MIN_WIDTH, belowFloor.width(), "clamp floor width");
        assertEquals(CarriageDims.MIN_HEIGHT, belowFloor.height(), "clamp floor height");

        CarriageDims aboveCeiling = CarriageDims.clamp(100, 100, 100);
        assertEquals(CarriageDims.MAX_LENGTH, aboveCeiling.length(), "clamp ceiling length");
        assertEquals(CarriageDims.MAX_WIDTH, aboveCeiling.width(), "clamp ceiling width");
        assertEquals(CarriageDims.MAX_HEIGHT, aboveCeiling.height(), "clamp ceiling height");

        // Pass-through: in-range values survive unchanged.
        CarriageDims inRange = CarriageDims.clamp(12, 7, 5);
        assertEquals(12, inRange.length(), "pass-through length");
        assertEquals(7, inRange.width(), "pass-through width");
        assertEquals(5, inRange.height(), "pass-through height");
    }
}
