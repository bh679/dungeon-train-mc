package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.train.CarriageContentsGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic coverage for {@link CarriageContentsGroupStore#findParentOf}. */
final class CarriageContentsGroupStoreReverseLookupTest {

    @BeforeEach
    void cleanSlate() {
        CarriageContentsGroupStore.clearCache();
    }

    @AfterEach
    void restoreEmpty() {
        CarriageContentsGroupStore.clearCache();
    }

    private static CarriageContentsGroup group(String... ids) {
        List<CarriageContentsGroup.Member> ms = new ArrayList<>();
        for (String id : ids) {
            ms.add(new CarriageContentsGroup.Member(id, 1));
        }
        return new CarriageContentsGroup(ms);
    }

    @Test
    @DisplayName("findParentOf: returns empty for an id that is not a member of any group")
    void findParentOf_emptyForNonMember() {
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_wooden", "container_metal"));
        assertTrue(CarriageContentsGroupStore.findParentOf("not_a_member").isEmpty());
        assertTrue(CarriageContentsGroupStore.findParentOf("container").isEmpty(),
            "parent itself is not a member of its own group (synthetic self is injected at resolve time, not stored)");
    }

    @Test
    @DisplayName("findParentOf: returns the parent id for a registered member")
    void findParentOf_returnsParent() {
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_wooden", "container_metal"));
        assertEquals("container", CarriageContentsGroupStore.findParentOf("container_wooden").orElseThrow());
        assertEquals("container", CarriageContentsGroupStore.findParentOf("container_metal").orElseThrow());
    }

    @Test
    @DisplayName("findParentOf: case-insensitive lookup")
    void findParentOf_caseInsensitive() {
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_wooden"));
        assertEquals("container", CarriageContentsGroupStore.findParentOf("CONTAINER_WOODEN").orElseThrow());
        assertEquals("container", CarriageContentsGroupStore.findParentOf("Container_Wooden").orElseThrow());
    }

    @Test
    @DisplayName("findParentOf: distinguishes between sibling groups")
    void findParentOf_acrossMultipleGroups() {
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_wooden", "container_metal"));
        CarriageContentsGroupStore.injectForTesting("furniture",
            group("chair", "table"));
        assertEquals("container", CarriageContentsGroupStore.findParentOf("container_wooden").orElseThrow());
        assertEquals("furniture", CarriageContentsGroupStore.findParentOf("chair").orElseThrow());
        assertEquals("furniture", CarriageContentsGroupStore.findParentOf("table").orElseThrow());
    }

    @Test
    @DisplayName("findParentOf: null returns empty (defensive)")
    void findParentOf_nullEmpty() {
        assertTrue(CarriageContentsGroupStore.findParentOf(null).isEmpty());
    }

    @Test
    @DisplayName("Cache invalidates on clearCache — fresh inject reflects in lookups")
    void cacheInvalidatesOnClear() {
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_wooden"));
        // Prime the cache
        assertEquals("container", CarriageContentsGroupStore.findParentOf("container_wooden").orElseThrow());

        CarriageContentsGroupStore.clearCache();
        assertTrue(CarriageContentsGroupStore.findParentOf("container_wooden").isEmpty(),
            "after clearCache, the inject should be gone");

        CarriageContentsGroupStore.injectForTesting("box",
            group("container_wooden"));
        assertEquals("box", CarriageContentsGroupStore.findParentOf("container_wooden").orElseThrow(),
            "new injection under a different parent should be reflected immediately");
    }

    @Test
    @DisplayName("allChildIds and findParentOf stay consistent after invalidation")
    void allChildIdsConsistent() {
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_wooden", "container_metal"));

        Optional<String> parentBefore = CarriageContentsGroupStore.findParentOf("container_wooden");
        assertTrue(CarriageContentsGroupStore.allChildIds().contains("container_wooden"));
        assertEquals("container", parentBefore.orElseThrow());

        // Re-inject with a different member set — must invalidate both caches.
        CarriageContentsGroupStore.invalidate("container");
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_barrel"));

        assertTrue(CarriageContentsGroupStore.findParentOf("container_wooden").isEmpty(),
            "old member should no longer have a parent");
        assertEquals("container", CarriageContentsGroupStore.findParentOf("container_barrel").orElseThrow());
        assertFalse(CarriageContentsGroupStore.allChildIds().contains("container_wooden"));
        assertTrue(CarriageContentsGroupStore.allChildIds().contains("container_barrel"));
    }
}
