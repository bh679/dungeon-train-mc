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
    @DisplayName("Allow-list is parent-only: excluding a member id has NO effect at resolution")
    void allowList_ignoredAtMemberLevel() {
        registerCustom("container");
        registerCustom("container_wooden");
        registerCustom("container_metal");
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_wooden", "1", "container_metal", "1"));

        // Allow-list excludes a member id directly. By design, the allow-list
        // operates at the parent level only — once container is picked, its
        // group resolution runs unfiltered. Wooden should STILL appear.
        CarriageContentsAllowList allow = CarriageContentsAllowList.EMPTY
            .withExcluded("default")
            .withExcluded("container_wooden");
        boolean sawWooden = false;
        for (int idx = 0; idx < 500; idx++) {
            CarriageContents picked = CarriageContentsRegistry.pick(0L, idx, allow);
            if ("container_wooden".equals(picked.id())) { sawWooden = true; break; }
        }
        assertTrue(sawWooden,
            "allow-list excludes are parent-only; excluding a sub-variant id directly must NOT remove it from its parent's pool");
    }

    @Test
    @DisplayName("Allow-list excluding parent stops the entire group from spawning")
    void allowList_excludingParent_killsGroup() {
        registerCustom("container");
        registerCustom("container_wooden");
        registerCustom("container_metal");
        registerCustom("other");
        CarriageContentsGroupStore.injectForTesting("container",
            group("container_wooden", "1", "container_metal", "1"));

        // Excluding the parent at top level → top-level pick never lands on
        // container → group never resolves → no member appears.
        CarriageContentsAllowList allow = CarriageContentsAllowList.EMPTY.withExcluded("container");
        for (int idx = 0; idx < 500; idx++) {
            String id = CarriageContentsRegistry.pick(0L, idx, allow).id();
            assertFalse("container".equals(id), "parent excluded; idx=" + idx);
            assertFalse("container_wooden".equals(id), "wooden excluded via parent; idx=" + idx);
            assertFalse("container_metal".equals(id), "metal excluded via parent; idx=" + idx);
        }
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
