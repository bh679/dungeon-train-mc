package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CarriageContentsRegistry} — pure-logic coverage only.
 *
 * <p>The {@link CarriageContentsRegistry#pick} deterministic seeded picker
 * and the registry add/remove/find API don't need a Forge/MC bootstrap. Methods
 * that would touch the filesystem ({@link CarriageContentsRegistry#reload})
 * are covered by the in-game Gate 2 test plan.</p>
 */
final class CarriageContentsRegistryTest {

    @BeforeEach
    void cleanSlate() {
        CarriageContentsRegistry.clear();
    }

    @AfterEach
    void restoreEmpty() {
        CarriageContentsRegistry.clear();
    }

    // ---- built-ins ----

    @Test
    @DisplayName("allContents: always includes built-ins in enum order after clear()")
    void allContents_includesBuiltinsAfterClear() {
        List<CarriageContents> all = CarriageContentsRegistry.allContents();
        assertFalse(all.isEmpty(), "built-ins should always be present");
        assertEquals("default", all.get(0).id());
        assertTrue(all.get(0).isBuiltin());
    }

    @Test
    @DisplayName("find: returns the default built-in by id")
    void find_returnsDefaultBuiltin() {
        var found = CarriageContentsRegistry.find("default");
        assertTrue(found.isPresent());
        assertEquals("default", found.get().id());
        assertTrue(found.get().isBuiltin());
    }

    @Test
    @DisplayName("find: returns empty for unknown id")
    void find_returnsEmptyForUnknown() {
        assertTrue(CarriageContentsRegistry.find("not_a_real_contents").isEmpty());
    }

    // ---- register / unregister ----

    @Test
    @DisplayName("register: adds a custom and makes it findable")
    void register_addsAndFinds() {
        CarriageContents.Custom c = (CarriageContents.Custom) CarriageContents.custom("furnished");
        assertTrue(CarriageContentsRegistry.register(c));
        assertTrue(CarriageContentsRegistry.find("furnished").isPresent());
    }

    @Test
    @DisplayName("register: rejects duplicate of existing custom")
    void register_rejectsDuplicate() {
        CarriageContents.Custom c = (CarriageContents.Custom) CarriageContents.custom("furnished");
        assertTrue(CarriageContentsRegistry.register(c));
        assertFalse(CarriageContentsRegistry.register(c));
    }

    @Test
    @DisplayName("unregister: built-in 'default' cannot be removed")
    void unregister_rejectsBuiltin() {
        assertFalse(CarriageContentsRegistry.unregister("default"));
        assertTrue(CarriageContentsRegistry.find("default").isPresent());
    }

    @Test
    @DisplayName("unregister: removes a registered custom")
    void unregister_removesCustom() {
        CarriageContents.Custom c = (CarriageContents.Custom) CarriageContents.custom("furnished");
        CarriageContentsRegistry.register(c);
        assertTrue(CarriageContentsRegistry.unregister("furnished"));
        assertTrue(CarriageContentsRegistry.find("furnished").isEmpty());
    }

    @Test
    @DisplayName("CarriageContents.custom rejects reserved built-in names")
    void customBuilder_rejectsReservedName() {
        assertThrows(IllegalArgumentException.class, () -> CarriageContents.custom("default"));
    }

    // ---- pick() determinism (weighted) ----
    //
    // pick() now uses weighted seeded selection (see CarriageContentsWeights).
    // With CarriageContentsWeights.current() == EMPTY in unit-test contexts
    // (no Forge bootstrap), every contents resolves to DEFAULT (=1) so the
    // distribution is uniform over the active set — these tests assert
    // *shape* (deterministic, varies-by-input, samples-the-set) rather than
    // pinning specific ids that would be brittle across pick-algorithm
    // refactors.

    @Test
    @DisplayName("pick: same seed + same carriage index always yields the same contents")
    void pick_isDeterministic() {
        CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom("a"));
        CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom("b"));

        CarriageContents first = CarriageContentsRegistry.pick(12345L, 7);
        for (int i = 0; i < 100; i++) {
            assertEquals(first.id(), CarriageContentsRegistry.pick(12345L, 7).id(), "call " + i);
        }
    }

    @Test
    @DisplayName("pick: varying carriage index samples the full registered set over many picks")
    void pick_variesByCarriageIndex() {
        CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom("a"));
        CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom("b"));
        // Registry now has 3 entries: default, a, b

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(CarriageContentsRegistry.pick(0xCAFEBABEL, i).id());
        }
        assertEquals(3, seen.size(),
            "500 seeded picks across varying indices should sample every registered contents");
    }

    @Test
    @DisplayName("pick: varying world seed shifts the pick at a fixed carriage index")
    void pick_variesBySeed() {
        CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom("a"));
        CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom("b"));

        Set<String> seen = new HashSet<>();
        for (long seed = 0; seed < 500; seed++) {
            seen.add(CarriageContentsRegistry.pick(seed, 42).id());
        }
        assertEquals(3, seen.size(),
            "500 different world seeds at the same carriage index should hit every contents");
    }

    @Test
    @DisplayName("pick: single-entry registry always returns that entry")
    void pick_singleEntryRegistry() {
        // Empty customs → only built-in 'default' remains
        CarriageContents picked = CarriageContentsRegistry.pick(0L, 0);
        assertEquals("default", picked.id());
        CarriageContents other = CarriageContentsRegistry.pick(Long.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals("default", other.id());
    }

    // ---- defaultPressurePlatePos ----

    @Test
    @DisplayName("defaultPressurePlatePos: returns interior floor centre for default 9×7×7 dims")
    void defaultPressurePlatePos_defaultDims() {
        BlockPos pos = CarriageContentsRegistry.defaultPressurePlatePos(CarriageDims.DEFAULT);
        // Interior is length-2=7, width-2=5, so centre is at (7/2, 0, 5/2) = (3, 0, 2)
        assertEquals(3, pos.getX());
        assertEquals(0, pos.getY());
        assertEquals(2, pos.getZ());
    }

    @Test
    @DisplayName("defaultPressurePlatePos: scales to smaller dims")
    void defaultPressurePlatePos_smallDims() {
        CarriageDims small = new CarriageDims(4, 3, 3);
        BlockPos pos = CarriageContentsRegistry.defaultPressurePlatePos(small);
        // Interior 2×1×1, centre (2/2, 0, 1/2) = (1, 0, 0)
        assertEquals(1, pos.getX());
        assertEquals(0, pos.getY());
        assertEquals(0, pos.getZ());
    }

    // ---- CarriageContents identity ----

    @Test
    @DisplayName("CarriageContents: Builtin and Custom have distinct ids and isBuiltin flags")
    void contents_identityFields() {
        CarriageContents builtin = CarriageContents.of(CarriageContents.ContentsType.DEFAULT);
        CarriageContents custom = CarriageContents.custom("fancy");
        assertNotNull(builtin);
        assertNotNull(custom);
        assertNotEquals(builtin.id(), custom.id());
        assertTrue(builtin.isBuiltin());
        assertFalse(custom.isBuiltin());
    }
}
