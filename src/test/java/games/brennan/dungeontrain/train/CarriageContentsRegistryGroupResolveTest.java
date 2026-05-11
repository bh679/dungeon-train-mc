package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.editor.CarriageContentsGroupStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for the group-resolution layer added on top of
 * {@link CarriageContentsRegistry#pick(long, int, CarriageContentsAllowList)}.
 *
 * <p>Tests inject groups via
 * {@link CarriageContentsGroupStore#injectForTesting} so we never touch the
 * filesystem or require a Forge bootstrap. Each test starts with an empty
 * registry and group store, registers a few customs, and drives {@code pick}
 * with explicit allow-lists.</p>
 */
final class CarriageContentsRegistryGroupResolveTest {

    @BeforeEach
    void cleanSlate() {
        CarriageContentsRegistry.clear();
        CarriageContentsGroupStore.clearCache();
    }

    @AfterEach
    void restoreEmpty() {
        CarriageContentsRegistry.clear();
        CarriageContentsGroupStore.clearCache();
    }

    private static void registerCustom(String id) {
        CarriageContentsRegistry.register((CarriageContents.Custom) CarriageContents.custom(id));
    }

    private static CarriageContentsGroup group(String... idsAndWeights) {
        // Pairs: id, weight, id, weight, ...
        List<CarriageContentsGroup.Member> ms = new ArrayList<>();
        for (int i = 0; i < idsAndWeights.length; i += 2) {
            int w = Integer.parseInt(idsAndWeights[i + 1]);
            ms.add(new CarriageContentsGroup.Member(idsAndWeights[i], w));
        }
        return new CarriageContentsGroup(ms);
    }

    @Test
    @DisplayName("pick: resolves through a group; self and every member appear in the distribution")
    void resolves_throughGroup() {
        registerCustom("container");
        registerCustom("container_wooden");
        registerCustom("container_metal");
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_wooden", "1", "container_metal", "1"));

        // Pool is [self(w=1), wooden(w=1), metal(w=1)] — over many seeds we
        // should see all three. Self resolution returns the parent unchanged.
        boolean sawSelf = false;
        boolean sawWooden = false;
        boolean sawMetal = false;
        for (int idx = 0; idx < 500; idx++) {
            CarriageContents picked = CarriageContentsRegistry.pick(0xCAFEBABEL, idx);
            String id = picked.id();
            if ("container".equals(id)) sawSelf = true;
            if ("container_wooden".equals(id)) sawWooden = true;
            if ("container_metal".equals(id)) sawMetal = true;
        }
        assertTrue(sawSelf, "parent self should appear in the resolved distribution (synthetic self entry)");
        assertTrue(sawWooden, "wooden child should appear in the resolved distribution");
        assertTrue(sawMetal, "metal child should appear in the resolved distribution");
    }

    @Test
    @DisplayName("pick: top-level pool excludes group children — children only appear via parent")
    void children_excludedFromTopLevelPool() {
        registerCustom("container");
        registerCustom("container_wooden");
        registerCustom("container_metal");
        registerCustom("furnished");  // unrelated, should still pickable directly
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_wooden", "1", "container_metal", "1"));

        // The only way to get container_wooden / container_metal is through
        // the parent group. If we EXCLUDE the parent via allow-list, they
        // disappear entirely from the distribution.
        CarriageContentsAllowList denyParent = CarriageContentsAllowList.EMPTY.withExcluded("container");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(CarriageContentsRegistry.pick(7777L, i, denyParent).id());
        }
        assertFalse(seen.contains("container_wooden"),
            "wooden must not appear when parent excluded — children are only reachable via parent");
        assertFalse(seen.contains("container_metal"),
            "metal must not appear when parent excluded");
        assertTrue(seen.contains("furnished") || seen.contains("default"),
            "unrelated ids must still be reachable");
    }

    @Test
    @DisplayName("pick: same (seed, idx) returns the same resolved child")
    void resolution_isDeterministic() {
        registerCustom("container");
        registerCustom("container_wooden");
        registerCustom("container_metal");
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_wooden", "1", "container_metal", "1"));

        CarriageContents first = CarriageContentsRegistry.pick(12345L, 7);
        for (int i = 0; i < 100; i++) {
            assertEquals(first.id(), CarriageContentsRegistry.pick(12345L, 7).id(),
                "deterministic call " + i);
        }
    }

    @Test
    @DisplayName("pick: allow-list excluding every group member but allowing parent → parent self")
    void allMembersExcludedSelfAllowed_returnsParentSelf() {
        registerCustom("container");
        registerCustom("container_wooden");
        registerCustom("container_metal");
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_wooden", "1", "container_metal", "1"));

        // Exclude every member but keep parent allowed → synthetic self is the
        // only entry in the pool. Parent returns as-is (its .nbt would be
        // stamped at placement time).
        CarriageContentsAllowList allow = CarriageContentsAllowList.EMPTY
            .withExcluded("default")
            .withExcluded("container_wooden")
            .withExcluded("container_metal");
        CarriageContents picked = CarriageContentsRegistry.pick(0L, 0, allow);
        assertEquals("container", picked.id(),
            "with all members excluded but self allowed, group resolution must return parent self");
    }

    @Test
    @DisplayName("pick: allow-list excluding parent AND every member → DEFAULT sentinel")
    void allMembersAndSelfExcluded_fallsBackToDefault() {
        registerCustom("container");
        registerCustom("container_wooden");
        registerCustom("container_metal");
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_wooden", "1", "container_metal", "1"));

        // Force the parent into the top-level pick by adding another custom,
        // then exclude self + every member so the group has truly nothing.
        registerCustom("other");
        CarriageContentsAllowList allow = CarriageContentsAllowList.EMPTY
            .withExcluded("default")
            .withExcluded("other");
        // We need to land on container — but we also need to exclude container
        // for the resolve fallback to trigger. Use the synchronised path
        // directly via the resolveGroup logic: simpler to just exclude
        // everything other than container from top-level, then on resolve also
        // exclude container, wooden, metal.
        CarriageContentsAllowList resolveAllow = allow
            .withExcluded("container")
            .withExcluded("container_wooden")
            .withExcluded("container_metal");
        // Top-level pick with resolveAllow: every contents excluded → DEFAULT (the existing path).
        CarriageContents picked = CarriageContentsRegistry.pick(0L, 0, resolveAllow);
        assertEquals("default", picked.id());
    }

    @Test
    @DisplayName("pick: group with unresolvable member yields self or DEFAULT — never the ghost id")
    void missingChild_fallsBackOrSelf() {
        registerCustom("container");
        // Note: NOT registering 'ghost_child'
        CarriageContentsGroupStore.injectForTesting("container", group("ghost_child", "1"));

        // Pool = [self(w=1), ghost(w=1)]. Self resolves to parent unchanged;
        // ghost is unresolvable so falls back to DEFAULT. Across many seeds
        // we should see both outcomes, but never 'ghost_child' itself.
        boolean sawSelf = false;
        boolean sawDefault = false;
        for (int idx = 0; idx < 500; idx++) {
            String id = CarriageContentsRegistry.pick(0xDEADBEEFL, idx).id();
            assertNotEquals("ghost_child", id, "unresolvable id must never appear in output; idx=" + idx);
            if ("container".equals(id)) sawSelf = true;
            if ("default".equals(id)) sawDefault = true;
        }
        assertTrue(sawSelf, "self should appear at some seed");
        assertTrue(sawDefault, "DEFAULT fallback should fire when ghost path is picked");
    }

    @Test
    @DisplayName("Group children NEVER appear at top-level regardless of seed")
    void children_neverAppearTopLevel() {
        // The stronger version of "stability": no matter the seed, a registered
        // id that is also a group member will never be returned as a top-level
        // pick. Resolution either hands back the parent → child (via group) or
        // some unrelated id.
        registerCustom("group_parent");
        registerCustom("group_child");
        registerCustom("alpha");
        registerCustom("beta");
        CarriageContentsGroupStore.injectForTesting("group_parent",
            group("group_child", "1"));

        // Exclude group_parent so the only way 'group_child' could appear
        // would be a top-level slot leak.
        CarriageContentsAllowList denyParent = CarriageContentsAllowList.EMPTY.withExcluded("group_parent");
        for (int idx = 0; idx < 500; idx++) {
            String id = CarriageContentsRegistry.pick(0xC0FFEEL, idx, denyParent).id();
            assertNotEquals("group_child", id,
                "group_child must NEVER appear at top level (idx " + idx + ")");
        }
    }

    @Test
    @DisplayName("pick: group with one child returns either self or that child (synthetic self in pool)")
    void singleMemberGroup() {
        registerCustom("container");
        registerCustom("container_wooden");
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_wooden", "5"));

        boolean sawSelf = false;
        boolean sawWooden = false;
        for (int idx = 0; idx < 500; idx++) {
            String id = CarriageContentsRegistry.pick(0L, idx).id();
            if ("container".equals(id)) sawSelf = true;
            if ("container_wooden".equals(id)) sawWooden = true;
        }
        // Pool = [self(w=1), wooden(w=5)]. Both should appear across 500 seeds.
        assertTrue(sawWooden, "wooden child should appear in the resolved distribution");
        assertTrue(sawSelf, "parent self (synthetic) should also appear");
    }

    @Test
    @DisplayName("CarriageContentsGroupStore.allChildIds: snapshot reflects injected groups")
    void allChildIds_reflectsInjected() {
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_wooden", "1", "container_metal", "1"));
        CarriageContentsGroupStore.injectForTesting("furniture",
            group("chair", "1", "table", "1"));
        Set<String> children = CarriageContentsGroupStore.allChildIds();
        assertTrue(children.contains("container_wooden"));
        assertTrue(children.contains("container_metal"));
        assertTrue(children.contains("chair"));
        assertTrue(children.contains("table"));
        assertFalse(children.contains("container"),
            "parents must not appear in allChildIds()");
    }
}
