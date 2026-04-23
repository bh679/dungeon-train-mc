package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.train.CarriageTemplate.CarriageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CarriageTemplate}'s pure-logic functions —
 * the deterministic carriage-variant selector
 * {@link CarriageTemplate#variantForIndex(int, CarriageGenerationConfig)},
 * the {@link CarriageType} enum contract, {@link CarriageVariant} validation,
 * the {@link CarriageDims} invariants + clamp helper, and the
 * {@link CarriageGenerationConfig} clamp semantics.
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

    private static final CarriageGenerationConfig LOOP = CarriageGenerationConfig.LOOPING;

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        // The registry is a global singleton; tests here manipulate its
        // custom-variant list, so reset it around each test to keep the
        // built-ins-only baseline for the cycle-length tests.
        CarriageVariantRegistry.clear();
    }

    // ---- variantForIndex LOOPING: built-ins in declared order (no customs) ----

    @Test
    @DisplayName("LOOPING: variantForIndex(0) returns STANDARD")
    void loop_zero_returnsStandard() {
        assertEquals("standard", CarriageTemplate.variantForIndex(0, LOOP).id());
    }

    @Test
    @DisplayName("LOOPING: variantForIndex(1) returns WINDOWED")
    void loop_one_returnsWindowed() {
        assertEquals("windowed", CarriageTemplate.variantForIndex(1, LOOP).id());
    }

    @Test
    @DisplayName("LOOPING: variantForIndex(2) returns SOLID_ROOF")
    void loop_two_returnsSolidRoof() {
        assertEquals("solid_roof", CarriageTemplate.variantForIndex(2, LOOP).id());
    }

    @Test
    @DisplayName("LOOPING: variantForIndex(3) returns FLATBED")
    void loop_three_returnsFlatbed() {
        assertEquals("flatbed", CarriageTemplate.variantForIndex(3, LOOP).id());
    }

    @Test
    @DisplayName("LOOPING: variantForIndex(4) wraps back to STANDARD — cycle of 4 with no customs")
    void loop_four_wrapsToStandard() {
        assertEquals("standard", CarriageTemplate.variantForIndex(4, LOOP).id());
    }

    @Test
    @DisplayName("LOOPING: variantForIndex(-1) returns FLATBED — Math.floorMod(-1, 4) == 3")
    void loop_negativeOne_returnsFlatbed() {
        // Math.floorMod distinguishes from Java's % operator: -1 % 4 == -1,
        // but Math.floorMod(-1, 4) == 3. The rolling-window manager depends
        // on negative indices wrapping forward (carriage index -1 is behind
        // the spawner), so this test pins the contract explicitly.
        assertEquals("flatbed", CarriageTemplate.variantForIndex(-1, LOOP).id());
    }

    @Test
    @DisplayName("LOOPING: variantForIndex(-4) returns STANDARD")
    void loop_negativeFour_returnsStandard() {
        assertEquals("standard", CarriageTemplate.variantForIndex(-4, LOOP).id());
    }

    @Test
    @DisplayName("LOOPING: variantForIndex(400) returns STANDARD — large multiples of cycle length still wrap")
    void loop_largeMultipleOfFour_returnsStandard() {
        assertEquals("standard", CarriageTemplate.variantForIndex(400, LOOP).id());
    }

    @Test
    @DisplayName("LOOPING: cycles through built-ins + customs in registration order")
    void loop_withCustoms_cyclesThroughAll() {
        // Register two customs — they are sorted alphabetically by the
        // registry, so 'aaa_custom' precedes 'zzz_custom' in the cycle.
        CarriageVariantRegistry.register((CarriageVariant.Custom) CarriageVariant.custom("aaa_custom"));
        CarriageVariantRegistry.register((CarriageVariant.Custom) CarriageVariant.custom("zzz_custom"));

        assertEquals("standard",    CarriageTemplate.variantForIndex(0, LOOP).id());
        assertEquals("windowed",    CarriageTemplate.variantForIndex(1, LOOP).id());
        assertEquals("solid_roof",  CarriageTemplate.variantForIndex(2, LOOP).id());
        assertEquals("flatbed",     CarriageTemplate.variantForIndex(3, LOOP).id());
        assertEquals("aaa_custom",  CarriageTemplate.variantForIndex(4, LOOP).id());
        assertEquals("zzz_custom",  CarriageTemplate.variantForIndex(5, LOOP).id());
        assertEquals("standard",    CarriageTemplate.variantForIndex(6, LOOP).id()); // wraps
    }

    // ---- variantForIndex RANDOM: deterministic, covers all variants ----

    @Test
    @DisplayName("RANDOM: same (seed, index) returns the same variant across repeated calls")
    void random_isDeterministic() {
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM, 4, 123456789L);
        String first = CarriageTemplate.variantForIndex(42, cfg).id();
        for (int i = 0; i < 100; i++) {
            assertEquals(first, CarriageTemplate.variantForIndex(42, cfg).id(), "call " + i);
        }
    }

    @Test
    @DisplayName("RANDOM: over 10 000 indices, all 4 built-ins appear (sanity check)")
    void random_coversAllVariants() {
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM, 4, 0xDEADBEEFL);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(CarriageTemplate.variantForIndex(i, cfg).id());
        }
        assertEquals(4, seen.size(), "expected all four built-in variants in a 10k-index sweep, saw: " + seen);
    }

    @Test
    @DisplayName("RANDOM: different seeds can produce different variants at the same index")
    void random_differentSeedsDiverge() {
        CarriageGenerationConfig a = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM, 4, 1L);
        CarriageGenerationConfig b = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM, 4, 2L);
        // Probabilistically: across 100 indices, at least one should differ
        // between two distinct seeds. P(all match) ≤ (1/4)^100.
        boolean anyDifferent = false;
        for (int i = 0; i < 100; i++) {
            if (!CarriageTemplate.variantForIndex(i, a).id()
                    .equals(CarriageTemplate.variantForIndex(i, b).id())) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue(anyDifferent, "two distinct seeds produced identical patterns across 100 indices");
    }

    @Test
    @DisplayName("RANDOM: custom variants are eligible in the pool")
    void random_includesCustoms() {
        CarriageVariantRegistry.register((CarriageVariant.Custom) CarriageVariant.custom("mycustom"));
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM, 4, 13L);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(CarriageTemplate.variantForIndex(i, cfg).id());
        }
        assertTrue(seen.contains("mycustom"),
                "registered custom variant never drawn in 500 RANDOM picks, saw: " + seen);
    }

    // ---- variantForIndex RANDOM_GROUPED: flatbed separator every groupSize+1 ----

    @Test
    @DisplayName("RANDOM_GROUPED: every (groupSize+1)th index is FLATBED")
    void grouped_separatorsAtFixedPositions() {
        int groupSize = 4;
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM_GROUPED, groupSize, 99L);
        int cycleLen = groupSize + 1;
        for (int cycle = 0; cycle < 5; cycle++) {
            int separatorIdx = cycle * cycleLen + groupSize;
            assertEquals("flatbed", CarriageTemplate.variantForIndex(separatorIdx, cfg).id(),
                    "separator at idx " + separatorIdx);
        }
    }

    @Test
    @DisplayName("RANDOM_GROUPED: indices inside the group are never FLATBED")
    void grouped_groupMembersNeverFlatbed() {
        int groupSize = 4;
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM_GROUPED, groupSize, 1234L);
        int cycleLen = groupSize + 1;
        for (int cycle = 0; cycle < 3; cycle++) {
            int base = cycle * cycleLen;
            for (int offset = 0; offset < groupSize; offset++) {
                String id = CarriageTemplate.variantForIndex(base + offset, cfg).id();
                assertNotEquals("flatbed", id,
                        "group member at idx " + (base + offset) + " should not be flatbed");
            }
        }
    }

    @Test
    @DisplayName("RANDOM_GROUPED: groupSize=1 alternates random+flatbed")
    void grouped_groupSizeOne() {
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM_GROUPED, 1, 7L);
        for (int i = 0; i < 20; i++) {
            if (i % 2 == 0) {
                assertNotEquals("flatbed", CarriageTemplate.variantForIndex(i, cfg).id(),
                        "group member at idx " + i);
            } else {
                assertEquals("flatbed", CarriageTemplate.variantForIndex(i, cfg).id(),
                        "separator at idx " + i);
            }
        }
    }

    @Test
    @DisplayName("RANDOM_GROUPED: same (seed, index, groupSize) returns the same variant repeatedly")
    void grouped_isDeterministic() {
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM_GROUPED, 4, 42L);
        String first = CarriageTemplate.variantForIndex(13, cfg).id();
        for (int i = 0; i < 100; i++) {
            assertEquals(first, CarriageTemplate.variantForIndex(13, cfg).id());
        }
    }

    @Test
    @DisplayName("RANDOM_GROUPED: negative indices wrap forward and separators still land every (groupSize+1)")
    void grouped_negativeIndexWrap() {
        int groupSize = 3;
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM_GROUPED, groupSize, 0L);
        int cycleLen = groupSize + 1;
        // floorMod(-1, 4) == 3 == groupSize, so index -1 is a separator in a 3-group layout.
        assertEquals("flatbed", CarriageTemplate.variantForIndex(-1, cfg).id());
        assertEquals("flatbed", CarriageTemplate.variantForIndex(-1 - cycleLen, cfg).id());
    }

    @Test
    @DisplayName("RANDOM_GROUPED: custom variants can fill group slots but never the separator")
    void grouped_customsInGroupSlotsOnly() {
        CarriageVariantRegistry.register((CarriageVariant.Custom) CarriageVariant.custom("mycustom"));
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM_GROUPED, 3, 55L);

        // Separator slots are always flatbed, even with customs registered.
        assertEquals("flatbed", CarriageTemplate.variantForIndex(3, cfg).id());
        assertEquals("flatbed", CarriageTemplate.variantForIndex(7, cfg).id());

        // Custom should appear in some group slot across a moderately-sized sweep.
        Set<String> groupSlotIds = new HashSet<>();
        for (int cycle = 0; cycle < 200; cycle++) {
            int base = cycle * 4;
            for (int offset = 0; offset < 3; offset++) {
                groupSlotIds.add(CarriageTemplate.variantForIndex(base + offset, cfg).id());
            }
        }
        assertTrue(groupSlotIds.contains("mycustom"),
                "custom never drawn in grouped mode's group slots, saw: " + groupSlotIds);
        assertTrue(!groupSlotIds.contains("flatbed"),
                "flatbed appeared in a group slot (should only appear at separator position): " + groupSlotIds);
    }

    // ---- CarriageGenerationConfig invariants ----

    @Test
    @DisplayName("CarriageGenerationConfig clamps groupSize below MIN up to MIN")
    void cfg_clampsGroupSizeBelowMin() {
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM_GROUPED, 0, 0L);
        assertEquals(CarriageGenerationConfig.MIN_GROUP_SIZE, cfg.groupSize());
    }

    @Test
    @DisplayName("CarriageGenerationConfig clamps groupSize above MAX down to MAX")
    void cfg_clampsGroupSizeAboveMax() {
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM_GROUPED, 9999, 0L);
        assertEquals(CarriageGenerationConfig.MAX_GROUP_SIZE, cfg.groupSize());
    }

    @Test
    @DisplayName("CarriageGenerationConfig passes through in-range groupSize untouched")
    void cfg_inRangeGroupSizePassthrough() {
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM_GROUPED, 7, 0L);
        assertEquals(7, cfg.groupSize());
    }

    @Test
    @DisplayName("CarriageGenerationConfig rejects a null mode")
    void cfg_rejectsNullMode() {
        assertThrows(IllegalArgumentException.class,
                () -> new CarriageGenerationConfig(null, 4, 0L));
    }

    @Test
    @DisplayName("CarriageGenerationConfig.DEFAULT uses RANDOM_GROUPED with group size 3")
    void cfg_defaultIsRandomGroupedThree() {
        assertEquals(CarriageGenerationMode.RANDOM_GROUPED, CarriageGenerationConfig.DEFAULT.mode());
        assertEquals(3, CarriageGenerationConfig.DEFAULT.groupSize());
    }

    @Test
    @DisplayName("CarriageGenerationConfig.clamp accepts a null mode (falls back to RANDOM_GROUPED)")
    void cfg_clampHandlesNullMode() {
        CarriageGenerationConfig cfg = CarriageGenerationConfig.clamp(null, 4, 0L);
        assertNotNull(cfg.mode());
        assertEquals(CarriageGenerationMode.RANDOM_GROUPED, cfg.mode());
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
