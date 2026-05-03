package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for the per-carriage allow-list overload of
 * {@link CarriageContentsRegistry#pick(long, int, CarriageContentsAllowList)}.
 * Uses the pure overload so tests don't touch the filesystem-backed
 * {@link games.brennan.dungeontrain.editor.CarriageVariantContentsAllowStore}.
 */
final class CarriageContentsRegistryFilterTest {

    @BeforeEach
    void cleanSlate() {
        CarriageContentsRegistry.clear();
    }

    @AfterEach
    void restoreEmpty() {
        CarriageContentsRegistry.clear();
    }

    @Test
    @DisplayName("EMPTY allow-list matches the legacy unfiltered pick")
    void empty_allow_matches_legacy() {
        long seed = 0x1234_5678_9ABC_DEF0L;
        for (int i = 0; i < 64; i++) {
            CarriageContents legacy = CarriageContentsRegistry.pick(seed, i);
            CarriageContents filtered = CarriageContentsRegistry.pick(seed, i, CarriageContentsAllowList.EMPTY);
            assertEquals(legacy.id(), filtered.id(), "carriage idx " + i);
        }
    }

    @Test
    @DisplayName("Null allow-list is treated as EMPTY (no filtering)")
    void null_allow_treated_as_empty() {
        CarriageContents result = CarriageContentsRegistry.pick(42L, 0, (CarriageContentsAllowList) null);
        assertNotNull(result);
        assertEquals("default", result.id());
    }

    @Test
    @DisplayName("Excluded id never gets picked")
    void excluded_id_never_picked() {
        // Register one custom alongside the builtin so we have ≥2 to choose from.
        CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom("furnished"));
        CarriageContentsAllowList denyDefault = CarriageContentsAllowList.EMPTY.withExcluded("default");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            CarriageContents picked = CarriageContentsRegistry.pick(0xCAFEBABEL, i, denyDefault);
            seen.add(picked.id());
            assertFalse("default".equals(picked.id()), "default should never be picked when excluded; idx=" + i);
        }
        assertTrue(seen.contains("furnished"), "furnished should be picked at least once");
    }

    @Test
    @DisplayName("All-excluded allow-list falls back to the DEFAULT builtin")
    void all_excluded_falls_back_to_default() {
        CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom("furnished"));
        CarriageContentsAllowList denyAll = CarriageContentsAllowList.EMPTY
            .withExcluded("default")
            .withExcluded("furnished");
        CarriageContents picked = CarriageContentsRegistry.pick(0L, 0, denyAll);
        assertEquals("default", picked.id());
        assertTrue(picked.isBuiltin());
    }

    @Test
    @DisplayName("Determinism preserved under filtering: same (seed, idx, allow) → same pick")
    void filter_is_deterministic() {
        CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom("furnished"));
        CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom("loot_room"));
        CarriageContentsAllowList allow = CarriageContentsAllowList.EMPTY.withExcluded("default");
        CarriageContents first = CarriageContentsRegistry.pick(7777L, 11, allow);
        for (int i = 0; i < 32; i++) {
            assertEquals(first.id(), CarriageContentsRegistry.pick(7777L, 11, allow).id(),
                "deterministic call " + i);
        }
    }

    @Test
    @DisplayName("Variant-aware overload with null variant matches legacy unfiltered pick")
    void null_variant_matches_legacy() {
        long seed = 0xDEADBEEFL;
        for (int i = 0; i < 32; i++) {
            CarriageContents legacy = CarriageContentsRegistry.pick(seed, i);
            CarriageContents byVariant = CarriageContentsRegistry.pick(seed, i, (CarriageVariant) null);
            assertEquals(legacy.id(), byVariant.id(), "idx " + i);
        }
    }
}
