package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.train.CarriagePlacer.CarriageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CarriagePlacer}'s pure-logic functions —
 * the deterministic carriage-variant selector
 * {@link CarriagePlacer#variantForIndex(int, CarriageGenerationConfig)},
 * the {@link CarriageType} enum contract, {@link CarriageVariant} validation,
 * the {@link CarriageDims} invariants + clamp helper, and the
 * {@link CarriageGenerationConfig} clamp semantics.
 *
 * <p>Runtime-dependent methods ({@code placeAt}, {@code eraseAt}) need a live
 * Forge/Minecraft server and are covered by the Forge GameTestServer integration
 * tests tracked separately on Project #16.</p>
 *
 * <p>These tests run in plain JUnit without a Forge/MC runtime. The class is
 * designed so {@code CarriagePlacer}'s own static init does not trigger
 * {@code Blocks.*} registry access — the {@code BlockState} fields are held
 * inside a lazy-init holder class that only loads when {@code placeAt} /
 * {@code eraseAt} are called from a live server path.</p>
 */
final class CarriagePlacerTest {

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
        assertEquals("standard", CarriagePlacer.variantForIndex(0, LOOP).id());
    }

    @Test
    @DisplayName("LOOPING: variantForIndex(1) returns WINDOWED")
    void loop_one_returnsWindowed() {
        assertEquals("windowed", CarriagePlacer.variantForIndex(1, LOOP).id());
    }

    @Test
    @DisplayName("LOOPING: variantForIndex(2) returns FLATBED")
    void loop_two_returnsFlatbed() {
        assertEquals("flatbed", CarriagePlacer.variantForIndex(2, LOOP).id());
    }

    @Test
    @DisplayName("LOOPING: variantForIndex(3) wraps back to STANDARD — cycle of 3 with no customs")
    void loop_three_wrapsToStandard() {
        assertEquals("standard", CarriagePlacer.variantForIndex(3, LOOP).id());
    }

    @Test
    @DisplayName("LOOPING: variantForIndex(4) returns WINDOWED — cycle of 3 wraps")
    void loop_four_returnsWindowed() {
        assertEquals("windowed", CarriagePlacer.variantForIndex(4, LOOP).id());
    }

    @Test
    @DisplayName("LOOPING: variantForIndex(-1) returns FLATBED — Math.floorMod(-1, 3) == 2")
    void loop_negativeOne_returnsFlatbed() {
        // Math.floorMod distinguishes from Java's % operator: -1 % 3 == -1,
        // but Math.floorMod(-1, 3) == 2. The rolling-window manager depends
        // on negative indices wrapping forward (carriage index -1 is behind
        // the spawner), so this test pins the contract explicitly.
        assertEquals("flatbed", CarriagePlacer.variantForIndex(-1, LOOP).id());
    }

    @Test
    @DisplayName("LOOPING: variantForIndex(-4) returns FLATBED — Math.floorMod(-4, 3) == 2")
    void loop_negativeFour_returnsFlatbed() {
        assertEquals("flatbed", CarriagePlacer.variantForIndex(-4, LOOP).id());
    }

    @Test
    @DisplayName("LOOPING: variantForIndex(399) returns STANDARD — large multiples of cycle length still wrap")
    void loop_largeMultipleOfThree_returnsStandard() {
        assertEquals("standard", CarriagePlacer.variantForIndex(399, LOOP).id());
    }

    @Test
    @DisplayName("LOOPING: cycles through built-ins + customs in registration order")
    void loop_withCustoms_cyclesThroughAll() {
        // Register two customs — they are sorted alphabetically by the
        // registry, so 'aaa_custom' precedes 'zzz_custom' in the cycle.
        CarriageVariantRegistry.register((CarriageVariant.Custom) CarriageVariant.custom("aaa_custom"));
        CarriageVariantRegistry.register((CarriageVariant.Custom) CarriageVariant.custom("zzz_custom"));

        assertEquals("standard",    CarriagePlacer.variantForIndex(0, LOOP).id());
        assertEquals("windowed",    CarriagePlacer.variantForIndex(1, LOOP).id());
        assertEquals("flatbed",     CarriagePlacer.variantForIndex(2, LOOP).id());
        assertEquals("aaa_custom",  CarriagePlacer.variantForIndex(3, LOOP).id());
        assertEquals("zzz_custom",  CarriagePlacer.variantForIndex(4, LOOP).id());
        assertEquals("standard",    CarriagePlacer.variantForIndex(5, LOOP).id()); // wraps
    }

    // ---- variantForIndex RANDOM: deterministic, covers all variants ----

    @Test
    @DisplayName("RANDOM: same (seed, index) returns the same variant across repeated calls")
    void random_isDeterministic() {
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM, 4, 123456789L);
        String first = CarriagePlacer.variantForIndex(42, cfg).id();
        for (int i = 0; i < 100; i++) {
            assertEquals(first, CarriagePlacer.variantForIndex(42, cfg).id(), "call " + i);
        }
    }

    @Test
    @DisplayName("RANDOM: over 10 000 indices, all 3 built-ins appear (sanity check)")
    void random_coversAllVariants() {
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM, 4, 0xDEADBEEFL);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(CarriagePlacer.variantForIndex(i, cfg).id());
        }
        assertEquals(3, seen.size(), "expected all three built-in variants in a 10k-index sweep, saw: " + seen);
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
            if (!CarriagePlacer.variantForIndex(i, a).id()
                    .equals(CarriagePlacer.variantForIndex(i, b).id())) {
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
            seen.add(CarriagePlacer.variantForIndex(i, cfg).id());
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
            assertEquals("flatbed", CarriagePlacer.variantForIndex(separatorIdx, cfg).id(),
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
                String id = CarriagePlacer.variantForIndex(base + offset, cfg).id();
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
                assertNotEquals("flatbed", CarriagePlacer.variantForIndex(i, cfg).id(),
                        "group member at idx " + i);
            } else {
                assertEquals("flatbed", CarriagePlacer.variantForIndex(i, cfg).id(),
                        "separator at idx " + i);
            }
        }
    }

    @Test
    @DisplayName("RANDOM_GROUPED: same (seed, index, groupSize) returns the same variant repeatedly")
    void grouped_isDeterministic() {
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM_GROUPED, 4, 42L);
        String first = CarriagePlacer.variantForIndex(13, cfg).id();
        for (int i = 0; i < 100; i++) {
            assertEquals(first, CarriagePlacer.variantForIndex(13, cfg).id());
        }
    }

    @Test
    @DisplayName("RANDOM_GROUPED: negative indices wrap forward and separators still land every (groupSize+1)")
    void grouped_negativeIndexWrap() {
        int groupSize = 3;
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM_GROUPED, groupSize, 0L);
        int cycleLen = groupSize + 1;
        // floorMod(-1, 4) == 3 == groupSize, so index -1 is a separator in a 3-group layout.
        assertEquals("flatbed", CarriagePlacer.variantForIndex(-1, cfg).id());
        assertEquals("flatbed", CarriagePlacer.variantForIndex(-1 - cycleLen, cfg).id());
    }

    @Test
    @DisplayName("RANDOM_GROUPED: custom variants can fill group slots but never the separator")
    void grouped_customsInGroupSlotsOnly() {
        CarriageVariantRegistry.register((CarriageVariant.Custom) CarriageVariant.custom("mycustom"));
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM_GROUPED, 3, 55L);

        // Separator slots are always flatbed, even with customs registered.
        assertEquals("flatbed", CarriagePlacer.variantForIndex(3, cfg).id());
        assertEquals("flatbed", CarriagePlacer.variantForIndex(7, cfg).id());

        // Custom should appear in some group slot across a moderately-sized sweep.
        Set<String> groupSlotIds = new HashSet<>();
        for (int cycle = 0; cycle < 200; cycle++) {
            int base = cycle * 4;
            for (int offset = 0; offset < 3; offset++) {
                groupSlotIds.add(CarriagePlacer.variantForIndex(base + offset, cfg).id());
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
    @DisplayName("CarriageType has exactly 3 variants in declared order")
    void carriageType_hasThreeVariantsInDeclaredOrder() {
        // Order is part of the public contract: variantForIndex cycles through
        // the registry whose built-in section uses CarriageType.values() in
        // declaration order, and the wiki Features table documents the
        // STANDARD → WINDOWED → FLATBED cycle. Reordering the enum silently
        // changes which variant spawns at any given index, so lock it here.
        assertArrayEquals(
            new CarriageType[] {
                CarriageType.STANDARD,
                CarriageType.WINDOWED,
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

    // ---- Weighted selection: the new overload variantForIndex(i, cfg, weights) ----

    @Test
    @DisplayName("Weighted: EMPTY weights reproduce the uniform RANDOM sequence — no regression for existing worlds")
    void weighted_emptyWeightsMatchUniformSequence() {
        // With every variant at DEFAULT=1, cumulative weights are 1,2,3,4 so
        // Random.nextInt(4) maps identically to the old variants.get(nextInt(4))
        // call. This is the core backwards-compat contract for worlds saved
        // before the weighting feature.
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM, 4, 0xCAFEBABEL);
        for (int i = 0; i < 500; i++) {
            assertEquals(
                CarriagePlacer.variantForIndex(i, cfg, CarriageWeights.EMPTY).id(),
                CarriagePlacer.variantForIndex(i, cfg).id(),
                "EMPTY weights should match default behaviour at index " + i
            );
        }
    }

    @Test
    @DisplayName("Weighted: weight=0 excludes a variant from the RANDOM pool")
    void weighted_zeroWeightExcludes() {
        CarriageWeights weights = new CarriageWeights(Map.of("windowed", 0));
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM, 4, 13L);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(CarriagePlacer.variantForIndex(i, cfg, weights).id());
        }
        assertFalse(seen.contains("windowed"),
                "weight=0 variant should never be picked, saw: " + seen);
        // Sanity: the other two built-ins still come through.
        assertTrue(seen.contains("standard"));
        assertTrue(seen.contains("flatbed"));
    }

    @Test
    @DisplayName("Weighted: a 2x weight roughly doubles draw frequency")
    void weighted_doubleWeightDoublesFrequency() {
        // standard=2, others=1 → expected share of 'standard' is 2/(2+1+1) = 0.5.
        // Over 20_000 samples with a fixed seed, a ±5% tolerance is loose
        // enough to avoid flakes but tight enough to catch a broken mapping.
        CarriageWeights weights = new CarriageWeights(Map.of(
                "standard", 2,
                "windowed", 1,
                "flatbed", 1
        ));
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM, 4, 0x1234567L);
        int total = 20_000;
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < total; i++) {
            counts.merge(CarriagePlacer.variantForIndex(i, cfg, weights).id(), 1, Integer::sum);
        }
        double standardShare = counts.getOrDefault("standard", 0) / (double) total;
        assertTrue(standardShare > 0.45 && standardShare < 0.55,
                "expected 'standard' share near 0.50, got " + standardShare + " (counts=" + counts + ")");
    }

    @Test
    @DisplayName("Weighted: RANDOM_GROUPED separator slot ignores flatbed weight")
    void weighted_groupedSeparatorIgnoresFlatbedWeight() {
        // flatbed=0 would exclude it from the random pool, but the separator
        // slot at every (groupSize+1)th index is a fixed visual rhythm and
        // must remain a flatbed regardless of weight.
        CarriageWeights weights = new CarriageWeights(Map.of("flatbed", 0));
        int groupSize = 3;
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM_GROUPED, groupSize, 77L);
        int cycleLen = groupSize + 1;
        for (int cycle = 0; cycle < 10; cycle++) {
            int separatorIdx = cycle * cycleLen + groupSize;
            assertEquals("flatbed",
                    CarriagePlacer.variantForIndex(separatorIdx, cfg, weights).id(),
                    "separator at idx " + separatorIdx + " must stay flatbed regardless of weight");
        }
    }

    @Test
    @DisplayName("Weighted: RANDOM_GROUPED group slots skip a flatbed-weight=0 variant")
    void weighted_groupedGroupSlotsRespectWeights() {
        // In RANDOM_GROUPED, group slots pick from the non-flatbed pool, which
        // excludes flatbed by construction. This test confirms setting a
        // non-flatbed weight to 0 keeps it out of the group slots too.
        CarriageWeights weights = new CarriageWeights(Map.of("windowed", 0));
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM_GROUPED, 3, 0L);
        int cycleLen = 4;
        for (int cycle = 0; cycle < 200; cycle++) {
            int base = cycle * cycleLen;
            for (int offset = 0; offset < 3; offset++) {
                String id = CarriagePlacer.variantForIndex(base + offset, cfg, weights).id();
                assertNotEquals("windowed", id,
                        "weight=0 variant should not appear in group slot at idx " + (base + offset));
            }
        }
    }

    @Test
    @DisplayName("Weighted: all-zero pool falls back to uniform (no empty-train crash)")
    void weighted_allZeroFallsBackToUniform() {
        CarriageWeights weights = new CarriageWeights(Map.of(
                "standard", 0,
                "windowed", 0,
                "flatbed", 0
        ));
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM, 4, 42L);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            seen.add(CarriagePlacer.variantForIndex(i, cfg, weights).id());
        }
        assertEquals(3, seen.size(),
                "all-zero pool should fall back to uniform, covering every built-in. Saw: " + seen);
    }

    @Test
    @DisplayName("Weighted: same (seed, index, weights) is deterministic across repeat calls")
    void weighted_isDeterministic() {
        CarriageWeights weights = new CarriageWeights(Map.of("standard", 5, "flatbed", 3));
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM, 4, 999L);
        String first = CarriagePlacer.variantForIndex(17, cfg, weights).id();
        for (int i = 0; i < 100; i++) {
            assertEquals(first, CarriagePlacer.variantForIndex(17, cfg, weights).id(), "call " + i);
        }
    }

    @Test
    @DisplayName("Weighted: unknown ids in the map are ignored (default 1 applied to real variants)")
    void weighted_unknownIdsAreIgnored() {
        // 'ghost' is not a registered variant, so its 50 weight is dead weight
        // with no effect on the pool. The four built-ins all get DEFAULT=1, so
        // the sequence should match EMPTY weights.
        CarriageWeights weights = new CarriageWeights(Map.of("ghost", 50));
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM, 4, 0xABCL);
        for (int i = 0; i < 200; i++) {
            assertEquals(
                CarriagePlacer.variantForIndex(i, cfg, CarriageWeights.EMPTY).id(),
                CarriagePlacer.variantForIndex(i, cfg, weights).id(),
                "unknown-id weight should not change output at idx " + i
            );
        }
    }

    @Test
    @DisplayName("Weighted: clamped weights (>MAX) behave as MAX")
    void weighted_abovemMaxClampsToMax() {
        CarriageWeights weights = new CarriageWeights(Map.of("standard", CarriageWeights.MAX + 999));
        // With standard at MAX(=100) and the others at DEFAULT(=1), standard's
        // share should be ~100/103 ≈ 0.97.
        CarriageGenerationConfig cfg = new CarriageGenerationConfig(CarriageGenerationMode.RANDOM, 4, 7L);
        int total = 5_000;
        int standardCount = 0;
        for (int i = 0; i < total; i++) {
            if ("standard".equals(CarriagePlacer.variantForIndex(i, cfg, weights).id())) {
                standardCount++;
            }
        }
        double share = standardCount / (double) total;
        assertTrue(share > 0.93 && share < 1.0,
                "expected 'standard' to dominate near 0.97 under max clamp, got " + share);
    }
}
