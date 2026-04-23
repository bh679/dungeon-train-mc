package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link CarriageTemplate}'s pure-logic functions —
 * the deterministic carriage-variant selector
 * {@link CarriageTemplate#variantForIndex(int)}, the {@link CarriageType}
 * enum contract, and the {@link CarriageDims} invariants + clamp helper.
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

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        // The registry is a global singleton; tests here manipulate its
        // custom-variant list, so reset it around each test to keep the
        // built-ins-only baseline for the cycle-length tests.
        CarriageVariantRegistry.clear();
    }

    // ---- variantForIndex: deterministic variant cycling ----

    @Test
    @DisplayName("variantForIndex(0) returns STANDARD")
    void variantForIndex_zero_returnsStandard() {
        assertEquals("standard", CarriageTemplate.variantForIndex(0).id());
    }

    @Test
    @DisplayName("variantForIndex(1) returns WINDOWED")
    void variantForIndex_one_returnsWindowed() {
        assertEquals("windowed", CarriageTemplate.variantForIndex(1).id());
    }

    @Test
    @DisplayName("variantForIndex(2) returns SOLID_ROOF")
    void variantForIndex_two_returnsSolidRoof() {
        assertEquals("solid_roof", CarriageTemplate.variantForIndex(2).id());
    }

    @Test
    @DisplayName("variantForIndex(3) returns FLATBED")
    void variantForIndex_three_returnsFlatbed() {
        assertEquals("flatbed", CarriageTemplate.variantForIndex(3).id());
    }

    @Test
    @DisplayName("variantForIndex(4) wraps back to STANDARD — cycle of 4 with no customs")
    void variantForIndex_four_wrapsToStandard() {
        assertEquals("standard", CarriageTemplate.variantForIndex(4).id());
    }

    @Test
    @DisplayName("variantForIndex(-1) returns FLATBED — Math.floorMod(-1, 4) == 3")
    void variantForIndex_negativeOne_returnsFlatbed() {
        // Math.floorMod distinguishes from Java's % operator: -1 % 4 == -1,
        // but Math.floorMod(-1, 4) == 3. The rolling-window manager depends
        // on negative indices wrapping forward (carriage index -1 is behind
        // the spawner), so this test pins the contract explicitly.
        assertEquals("flatbed", CarriageTemplate.variantForIndex(-1).id());
    }

    @Test
    @DisplayName("variantForIndex(-4) returns STANDARD")
    void variantForIndex_negativeFour_returnsStandard() {
        assertEquals("standard", CarriageTemplate.variantForIndex(-4).id());
    }

    @Test
    @DisplayName("variantForIndex(400) returns STANDARD — large multiples of cycle length still wrap")
    void variantForIndex_largeMultipleOfFour_returnsStandard() {
        assertEquals("standard", CarriageTemplate.variantForIndex(400).id());
    }

    @Test
    @DisplayName("variantForIndex cycles through built-ins + customs in registration order")
    void variantForIndex_withCustoms_cyclesThroughAll() {
        // Register two customs — they are sorted alphabetically by the
        // registry, so 'aaa_custom' precedes 'zzz_custom' in the cycle.
        CarriageVariantRegistry.register((CarriageVariant.Custom) CarriageVariant.custom("aaa_custom"));
        CarriageVariantRegistry.register((CarriageVariant.Custom) CarriageVariant.custom("zzz_custom"));

        assertEquals("standard",    CarriageTemplate.variantForIndex(0).id());
        assertEquals("windowed",    CarriageTemplate.variantForIndex(1).id());
        assertEquals("solid_roof",  CarriageTemplate.variantForIndex(2).id());
        assertEquals("flatbed",     CarriageTemplate.variantForIndex(3).id());
        assertEquals("aaa_custom",  CarriageTemplate.variantForIndex(4).id());
        assertEquals("zzz_custom",  CarriageTemplate.variantForIndex(5).id());
        assertEquals("standard",    CarriageTemplate.variantForIndex(6).id()); // wraps
    }

    // ---- CarriageType enum contract ----

    @Test
    @DisplayName("CarriageType has exactly 4 variants in declared order")
    void carriageType_hasFourVariantsInDeclaredOrder() {
        // Order is part of the public contract: variantForIndex cycles through
        // the registry whose built-in section uses CarriageType.values() in
        // declaration order, and the wiki Features table documents the
        // STANDARD → WINDOWED → SOLID_ROOF → FLATBED cycle. Reordering the
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

    // ---- CarriageVariant validation ----

    @Test
    @DisplayName("CarriageVariant.custom rejects uppercase, spaces, and reserved names")
    void carriageVariant_customRejectsInvalidNames() {
        assertThrows(IllegalArgumentException.class,
            () -> CarriageVariant.custom("Invalid"), "uppercase");
        assertThrows(IllegalArgumentException.class,
            () -> CarriageVariant.custom("has space"), "space");
        assertThrows(IllegalArgumentException.class,
            () -> CarriageVariant.custom(""), "empty");
        assertThrows(IllegalArgumentException.class,
            () -> CarriageVariant.custom("standard"), "reserved built-in");
        assertThrows(IllegalArgumentException.class,
            () -> CarriageVariant.custom("flatbed"), "reserved built-in");
    }

    // ---- CarriageDims: default + invariants ----

    @Test
    @DisplayName("CarriageDims.DEFAULT is 9x7x7 — the shipped footprint")
    void carriageDims_default_is9x7x7() {
        // DEFAULT drives every code path that hasn't got a WorldData dims
        // tag yet: legacy world saves (missing NBT tags fall back to
        // CarriageDims.DEFAULT), the options-screen initial field values,
        // and integration-test fixtures. Any change needs a paired wiki +
        // doc update.
        assertEquals(9, CarriageDims.DEFAULT.length(), "length");
        assertEquals(7, CarriageDims.DEFAULT.width(), "width");
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
