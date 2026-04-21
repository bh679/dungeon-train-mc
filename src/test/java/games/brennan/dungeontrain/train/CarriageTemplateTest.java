package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link CarriageTemplate}'s pure-logic functions —
 * the deterministic carriage-variant selector {@link CarriageTemplate#typeForIndex(int)},
 * the {@link CarriageType} enum contract, and the public dimension constants.
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

    // ---- Dimension constants ----

    @Test
    @DisplayName("LENGTH/WIDTH/HEIGHT match documented carriage footprint (9x5x4)")
    void constants_haveExpectedDimensions() {
        // These constants drive TrainAssembler.spawnTrain's offset math,
        // TrainWindowManager's carriage-index projection, and the wiki
        // "9 x 5 x 4 (length x width x height)" documentation. Any change
        // needs a paired wiki + doc update.
        assertEquals(9, CarriageTemplate.LENGTH, "LENGTH");
        assertEquals(5, CarriageTemplate.WIDTH, "WIDTH");
        assertEquals(4, CarriageTemplate.HEIGHT, "HEIGHT");
    }
}
