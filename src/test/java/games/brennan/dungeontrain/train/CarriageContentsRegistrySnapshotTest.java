package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the registry snapshot behind {@link CarriageContentsRegistry#allContents()} and
 * {@link CarriageVariantRegistry#allVariants()}.
 *
 * <p>The editor overlay resolves every template's plot origin every tick and the origin lookups
 * key their memo on the snapshot's identity — so the snapshot must be the same object until the
 * registry mutates, and a different object as soon as it does. A snapshot that changed identity
 * on every call would silently put the O(n²) walk back; one that survived a mutation would hide
 * a freshly registered template from the editor row.</p>
 */
final class CarriageContentsRegistrySnapshotTest {

    private static final String CONTENTS_ID = "snapshot_test_contents";
    private static final String VARIANT_ID = "snapshot_test_variant";

    @BeforeEach
    @AfterEach
    void cleanRegistries() {
        CarriageContentsRegistry.unregister(CONTENTS_ID);
        CarriageVariantRegistry.unregister(VARIANT_ID);
    }

    @Test
    @DisplayName("allContents returns one object until the registry mutates")
    void contentsSnapshotIsStableThenInvalidated() {
        List<CarriageContents> a = CarriageContentsRegistry.allContents();
        List<CarriageContents> b = CarriageContentsRegistry.allContents();
        assertSame(a, b, "steady state must hand out the same snapshot — the origin index keys on it");

        assertTrue(CarriageContentsRegistry.register(new CarriageContents.Custom(CONTENTS_ID)));
        List<CarriageContents> afterAdd = CarriageContentsRegistry.allContents();
        assertNotSame(a, afterAdd, "register must produce a new snapshot");
        assertTrue(afterAdd.stream().anyMatch(c -> c.id().equals(CONTENTS_ID)));
        assertEquals(a.size() + 1, afterAdd.size());

        assertTrue(CarriageContentsRegistry.unregister(CONTENTS_ID));
        List<CarriageContents> afterRemove = CarriageContentsRegistry.allContents();
        assertNotSame(afterAdd, afterRemove, "unregister must produce a new snapshot");
        assertEquals(a, afterRemove, "and it must equal the original contents, in order");
    }

    @Test
    @DisplayName("allVariants returns one object until the registry mutates")
    void variantsSnapshotIsStableThenInvalidated() {
        List<CarriageVariant> a = CarriageVariantRegistry.allVariants();
        assertSame(a, CarriageVariantRegistry.allVariants());

        assertTrue(CarriageVariantRegistry.register(new CarriageVariant.Custom(VARIANT_ID)));
        List<CarriageVariant> afterAdd = CarriageVariantRegistry.allVariants();
        assertNotSame(a, afterAdd);
        assertTrue(afterAdd.stream().anyMatch(v -> v.id().equals(VARIANT_ID)));

        assertTrue(CarriageVariantRegistry.unregister(VARIANT_ID));
        assertEquals(a, CarriageVariantRegistry.allVariants());
    }

    @Test
    @DisplayName("the shared snapshot is immutable")
    void snapshotIsImmutable() {
        List<CarriageContents> all = CarriageContentsRegistry.allContents();
        assertThrows(UnsupportedOperationException.class, () -> all.add(new CarriageContents.Custom("x")));
        List<CarriageVariant> variants = CarriageVariantRegistry.allVariants();
        assertThrows(UnsupportedOperationException.class, variants::clear);
    }

    @Test
    @DisplayName("built-ins come first, customs alphabetical — order the plot row depends on")
    void orderIsBuiltinsThenCustomsSorted() {
        List<CarriageContents> all = CarriageContentsRegistry.allContents();
        int builtins = CarriageContentsRegistry.builtins().size();
        assertEquals(CarriageContentsRegistry.builtins(), all.subList(0, builtins));
        String prev = "";
        for (CarriageContents c : all.subList(builtins, all.size())) {
            assertTrue(c instanceof CarriageContents.Custom);
            assertTrue(c.id().compareTo(prev) >= 0, "customs must stay sorted: " + prev + " before " + c.id());
            prev = c.id();
        }
    }
}
