package games.brennan.dungeontrain.worldgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for {@link DtSurfaceRules#nonVanillaRules} — the namespace filter
 * {@code SurfaceRuleManagerMixin} applies to TerraBlender's per-category rule map. Rule values are
 * plain strings here; the helper never looks inside them.
 */
final class DtSurfaceRulesTest {

    @Test
    @DisplayName("a category TerraBlender has no map for reads as empty")
    void missingCategoryIsEmpty() {
        assertTrue(DtSurfaceRules.nonVanillaRules(null).isEmpty());
    }

    @Test
    @DisplayName("an empty map stays empty")
    void emptyStaysEmpty() {
        assertTrue(DtSurfaceRules.nonVanillaRules(Map.of()).isEmpty());
    }

    @Test
    @DisplayName("only the minecraft namespace leaves nothing to layer")
    void onlyVanillaIsEmpty() {
        assertTrue(DtSurfaceRules.nonVanillaRules(Map.of("minecraft", "tb-default")).isEmpty());
    }

    @Test
    @DisplayName("other namespaces survive, minecraft is dropped")
    void keepsModdedNamespaces() {
        Map<String, String> in = Map.of("minecraft", "tb-default", "biomesoplenty", "bop");
        assertEquals(Map.of("biomesoplenty", "bop"), DtSurfaceRules.nonVanillaRules(in));
    }

    @Test
    @DisplayName("the input is not mutated and the result is immutable")
    void inputUntouchedResultImmutable() {
        Map<String, String> in = new HashMap<>(Map.of("minecraft", "tb-default", "biomesoplenty", "bop"));
        Map<String, String> out = DtSurfaceRules.nonVanillaRules(in);
        assertEquals(2, in.size());
        assertThrows(UnsupportedOperationException.class, () -> out.put("x", "y"));
    }
}
