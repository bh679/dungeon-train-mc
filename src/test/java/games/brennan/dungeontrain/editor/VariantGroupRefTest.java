package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for v9 lock-group references — an option inside one cell's
 * candidate list that resolves to whatever a <i>different</i> lock group
 * rolled ({@link VariantGroupRefs}, {@link VariantGroupResolver}).
 *
 * <p>Two properties carry the feature and both are easy to lose silently.
 * <b>Agreement:</b> when the reference wins the roll, the cell must render the
 * exact block the referenced group's own cells rendered — not merely a block
 * from the same pool. <b>Termination:</b> a reference graph is author-editable
 * and file-editable, so cycles are reachable; the pool must shed them before
 * the roll rather than dead-ending during the walk.</p>
 *
 * <p><b>Subset order</b> is the third: a group that references another
 * requires it, and {@code analyse()} must order the sidecar so no group is
 * resolved before the groups it requires. Liveness and every group's pool
 * are derived along that order in one pass, so an order that regressed would
 * quietly produce a half-resolved graph rather than an error.</p>
 *
 * <p>Uses {@link CarriageVariantBlocks} as the concrete sidecar (the other
 * three route through the same {@link VariantGroupResolver}), and needs a
 * headless Minecraft bootstrap so {@link VariantState}'s {@code BlockState}
 * resolves.</p>
 */
final class VariantGroupRefTest {

    /** Wide enough that a shared-vs-coincidental agreement can't survive by luck. */
    private static final int SEEDS = 24;
    private static final int INDICES = 24;

    private static final BlockPos CELL_1 = new BlockPos(1, 1, 1);
    private static final BlockPos CELL_1B = new BlockPos(1, 1, 2);
    private static final BlockPos CELL_2 = new BlockPos(2, 1, 1);
    private static final BlockPos CELL_3 = new BlockPos(3, 1, 1);
    private static final BlockPos CELL_LOOSE = new BlockPos(4, 1, 1);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static VariantState of(Block block) {
        return VariantState.of(block.defaultBlockState());
    }

    private static VariantState ref(int group) {
        // The placeholder is deliberately a block that appears in no pool, so
        // any test that sees BEDROCK is seeing an unfollowed reference.
        return VariantState.ofGroupRef(group, Blocks.BEDROCK.defaultBlockState());
    }

    /** Group 1's pool — three blocks that appear in no other group. */
    private static List<VariantState> groupOnePool() {
        return List.of(of(Blocks.STONE), of(Blocks.ANDESITE), of(Blocks.DIORITE));
    }

    private static final Set<Block> GROUP_ONE_BLOCKS =
        Set.of(Blocks.STONE, Blocks.ANDESITE, Blocks.DIORITE);

    /**
     * Group 1 = [stone, andesite, diorite] at two cells; group 2 = [oak, birch,
     * →group 1]. The canonical shape from the feature request.
     */
    private static CarriageVariantBlocks twoGroups() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_1, groupOnePool());
        sc.put(CELL_1B, groupOnePool());
        sc.setLockId(CELL_1, 1);
        sc.setLockId(CELL_1B, 1);
        sc.put(CELL_2, List.of(of(Blocks.OAK_PLANKS), of(Blocks.BIRCH_PLANKS), ref(1)));
        sc.setLockId(CELL_2, 2);
        return sc;
    }

    private static Block blockOf(VariantState s) {
        return s == null ? null : s.state().getBlock();
    }

    @Test
    @DisplayName("a reference renders exactly what the referenced group rendered")
    void referenceAgreesWithTheGroupItPointsAt() {
        CarriageVariantBlocks sc = twoGroups();
        int deferred = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            for (int idx = 0; idx < INDICES; idx++) {
                Block groupOne = blockOf(sc.resolve(CELL_1, seed, idx));
                Block groupOneSibling = blockOf(sc.resolve(CELL_1B, seed, idx));
                Block groupTwo = blockOf(sc.resolve(CELL_2, seed, idx));

                assertEquals(groupOne, groupOneSibling,
                    "group 1's own cells disagreed at seed " + seed + " index " + idx
                        + " — the lock itself is broken, not the reference");
                assertTrue(GROUP_ONE_BLOCKS.contains(groupOne), "group 1 rolled outside its pool");

                if (groupTwo == Blocks.OAK_PLANKS || groupTwo == Blocks.BIRCH_PLANKS) continue;
                deferred++;
                assertEquals(groupOne, groupTwo,
                    "the reference resolved to a different block than group 1 did, at seed "
                        + seed + " index " + idx);
            }
        }
        assertTrue(deferred > 0,
            "the reference never won a roll, so agreement was never actually exercised");
    }

    @Test
    @DisplayName("a reference never leaks its placeholder block")
    void placeholderIsNeverPlaced() {
        CarriageVariantBlocks sc = twoGroups();
        for (int seed = 0; seed < SEEDS; seed++) {
            for (int idx = 0; idx < INDICES; idx++) {
                assertFalse(blockOf(sc.resolve(CELL_2, seed, idx)) == Blocks.BEDROCK,
                    "placeholder surfaced instead of the referenced value at seed "
                        + seed + " index " + idx);
            }
        }
    }

    @Test
    @DisplayName("chained references walk all the way down to a real value")
    void chainedReferencesTerminate() {
        // 3 → 2 → 1, with the references weighted heavily so the chain is the
        // usual outcome rather than a rare one.
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_1, groupOnePool());
        sc.setLockId(CELL_1, 1);
        sc.put(CELL_2, List.of(of(Blocks.OAK_PLANKS), ref(1).withWeight(50)));
        sc.setLockId(CELL_2, 2);
        sc.put(CELL_3, List.of(of(Blocks.GLASS), ref(2).withWeight(50)));
        sc.setLockId(CELL_3, 3);

        int chained = 0;
        for (int seed = 0; seed < SEEDS; seed++) {
            for (int idx = 0; idx < INDICES; idx++) {
                Block deep = blockOf(sc.resolve(CELL_3, seed, idx));
                assertNotNull(deep, "chain produced no value at seed " + seed + " index " + idx);
                if (GROUP_ONE_BLOCKS.contains(deep)) {
                    chained++;
                    assertEquals(blockOf(sc.resolve(CELL_1, seed, idx)), deep,
                        "two-hop chain landed on a different group 1 value than group 1 itself");
                } else {
                    assertTrue(deep == Blocks.GLASS || deep == Blocks.OAK_PLANKS,
                        "chain produced an unrelated block: " + deep);
                }
            }
        }
        assertTrue(chained > 0, "the two-hop chain never fired");
    }

    @Test
    @DisplayName("a self-reference is dropped and the cell re-rolls among the rest")
    void selfReferenceIsDropped() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_1, List.of(of(Blocks.STONE), ref(1)));
        sc.setLockId(CELL_1, 1);
        for (int seed = 0; seed < SEEDS; seed++) {
            for (int idx = 0; idx < INDICES; idx++) {
                assertEquals(Blocks.STONE, blockOf(sc.resolve(CELL_1, seed, idx)),
                    "a cell that references its own group must fall back to its other options");
            }
        }
    }

    @Test
    @DisplayName("a reference to a group that does not exist is dropped, not placed")
    void danglingReferenceIsDropped() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_LOOSE, List.of(of(Blocks.STONE), ref(9)));  // no cell carries lock-id 9
        for (int seed = 0; seed < SEEDS; seed++) {
            for (int idx = 0; idx < INDICES; idx++) {
                assertEquals(Blocks.STONE, blockOf(sc.resolve(CELL_LOOSE, seed, idx)),
                    "a dangling reference must re-roll among the survivors");
            }
        }
    }

    @Test
    @DisplayName("mutual references resolve rather than dead-ending mid-walk")
    void mutualReferencesStillProduceAValue() {
        // The case a group-level liveness check waves through: both groups have
        // a concrete option, so both "resolve", yet a walk 1 → 2 → 1 has
        // nowhere to land. Excluding back-edges is what keeps this terminating.
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_1, List.of(of(Blocks.STONE), ref(2)));
        sc.setLockId(CELL_1, 1);
        sc.put(CELL_2, List.of(of(Blocks.OAK_PLANKS), ref(1)));
        sc.setLockId(CELL_2, 2);

        for (int seed = 0; seed < SEEDS; seed++) {
            for (int idx = 0; idx < INDICES; idx++) {
                for (BlockPos cell : List.of(CELL_1, CELL_2)) {
                    Block got = blockOf(sc.resolve(cell, seed, idx));
                    assertNotNull(got, "mutual reference dead-ended at " + cell
                        + " seed " + seed + " index " + idx);
                    assertTrue(got == Blocks.STONE || got == Blocks.OAK_PLANKS,
                        "unexpected block from a mutual pair: " + got);
                }
            }
        }
    }

    @Test
    @DisplayName("a group whose every option is a dead reference is itself dead")
    void deadnessPropagates() {
        // Group 2's only reference points at the non-existent group 9, so group
        // 2 resolves to oak alone; group 3 references group 2, which is alive,
        // so group 3 still works. Group 4's sole option references group 5,
        // which has no cells at all — group 4 has nothing left to roll.
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_2, List.of(of(Blocks.OAK_PLANKS), ref(9)));
        sc.setLockId(CELL_2, 2);
        sc.put(CELL_3, List.of(of(Blocks.GLASS), ref(2)));
        sc.setLockId(CELL_3, 3);
        sc.put(CELL_LOOSE, List.of(ref(5), ref(9)));
        sc.setLockId(CELL_LOOSE, 4);

        for (int seed = 0; seed < 8; seed++) {
            assertEquals(Blocks.OAK_PLANKS, blockOf(sc.resolve(CELL_2, seed, 0)));
            Block three = blockOf(sc.resolve(CELL_3, seed, 0));
            assertTrue(three == Blocks.GLASS || three == Blocks.OAK_PLANKS,
                "a live reference through a partly-dead group should still work, got " + three);
            assertNull(sc.resolve(CELL_LOOSE, seed, 0),
                "a cell with nothing but dead references has no value — the template block stands");
        }
    }


    // ---- subset order -------------------------------------------------------
    //
    // A group that references another requires it; the referenced group is that
    // group's subset. analyse() orders the sidecar subset-first so liveness and
    // the per-group pools can be derived in one forward pass.

    /** Position of {@code lockId} in the graph's subset order. */
    private static int orderOf(CarriageVariantBlocks sc, int lockId) {
        int at = sc.groupRefs().graph().order().indexOf(lockId);
        assertTrue(at >= 0, "lock-id " + lockId + " missing from the subset order");
        return at;
    }

    @Test
    @DisplayName("a chain orders subset-first, and every group in use appears exactly once")
    void chainOrdersSubsetsFirst() {
        // 3 → 2 → 1: group 1 has no subsets, so it leads; 3 requires 2 requires 1.
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_1, groupOnePool());
        sc.setLockId(CELL_1, 1);
        sc.put(CELL_2, List.of(of(Blocks.OAK_PLANKS), ref(1)));
        sc.setLockId(CELL_2, 2);
        sc.put(CELL_3, List.of(of(Blocks.GLASS), ref(2)));
        sc.setLockId(CELL_3, 3);

        List<Integer> order = sc.groupRefs().graph().order();
        assertEquals(List.of(1, 2, 3), order, "a straight chain must come out subset-first");
        assertEquals(order.size(), new LinkedHashSet<>(order).size(), "a group was ordered twice");
    }

    @Test
    @DisplayName("groups with no subsets lead, whatever order they were authored in")
    void subsetFreeGroupsLead() {
        // Authored dependents-first, and with an independent group last, so an
        // order that merely echoed insertion order would fail this.
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_3, List.of(of(Blocks.GLASS), ref(1), ref(2)));
        sc.setLockId(CELL_3, 3);
        sc.put(CELL_1, groupOnePool());
        sc.setLockId(CELL_1, 1);
        sc.put(CELL_2, List.of(of(Blocks.OAK_PLANKS), ref(1)));
        sc.setLockId(CELL_2, 2);
        sc.put(CELL_LOOSE, List.of(of(Blocks.BRICKS), of(Blocks.GRAVEL)));
        sc.setLockId(CELL_LOOSE, 4);

        assertTrue(orderOf(sc, 1) < orderOf(sc, 2), "1 is 2's subset and must precede it");
        assertTrue(orderOf(sc, 2) < orderOf(sc, 3), "2 is 3's subset and must precede it");
        assertTrue(orderOf(sc, 1) < orderOf(sc, 3), "1 is also 3's subset");
        assertTrue(orderOf(sc, 4) < orderOf(sc, 3),
            "a group with no subsets must lead one that has them");
    }

    @Test
    @DisplayName("a mutual pair has no subsets at all — both edges are back-edges")
    void mutualPairIsSubsetFree() {
        // Rule 1 excludes both directions, so neither group requires the other
        // and the order is free to list them in id order. The pair must still
        // appear in full: an order that dropped a tangled group would take its
        // pool with it.
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_1, List.of(of(Blocks.STONE), ref(2)));
        sc.setLockId(CELL_1, 1);
        sc.put(CELL_2, List.of(of(Blocks.OAK_PLANKS), ref(1)));
        sc.setLockId(CELL_2, 2);

        List<Integer> order = sc.groupRefs().graph().order();
        assertEquals(2, order.size(), "both halves of a mutual pair must be ordered");
        assertTrue(order.containsAll(List.of(1, 2)));
    }

    @Test
    @DisplayName("dangling and self references are not subsets and don't delay a group")
    void deadReferencesAreNotSubsets() {
        // Group 2's references go nowhere real, so it has nothing to wait for.
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_1, groupOnePool());
        sc.setLockId(CELL_1, 1);
        sc.put(CELL_2, List.of(of(Blocks.OAK_PLANKS), ref(2), ref(9)));
        sc.setLockId(CELL_2, 2);

        List<Integer> order = sc.groupRefs().graph().order();
        assertEquals(2, order.size());
        assertTrue(order.containsAll(List.of(1, 2)));
    }

    // ---- precomputed pools --------------------------------------------------

    @Test
    @DisplayName("each group's precomputed pool is what filtering that group's list yields")
    void precomputedPoolsMatchTheFilter() {
        // Covers a plain group (1), a live reference (3 → 2), a partly-dead list
        // (2, whose ref(9) dangles) and a wholly-dead one (4).
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_1, groupOnePool());
        sc.setLockId(CELL_1, 1);
        sc.put(CELL_2, List.of(of(Blocks.OAK_PLANKS), ref(9)));
        sc.setLockId(CELL_2, 2);
        sc.put(CELL_3, List.of(of(Blocks.GLASS), ref(2)));
        sc.setLockId(CELL_3, 3);
        sc.put(CELL_LOOSE, List.of(ref(5), ref(9)));
        sc.setLockId(CELL_LOOSE, 4);

        VariantGroupResolver refs = sc.groupRefs();
        VariantGroupRefs.Graph graph = refs.graph();
        for (int id : graph.order()) {
            List<VariantState> states = refs.statesForLockId(id);
            assertNotNull(states, "group " + id + " is in the order but has no cells");
            List<VariantState> expected = VariantGroupRefs.eligible(states, id, graph);
            VariantGroupRefs.Pool pool = graph.poolFor(id);
            if (expected.isEmpty()) {
                assertNull(pool, "group " + id + " has nothing to roll, so it should have no pool");
                continue;
            }
            assertNotNull(pool, "group " + id + " should have a precomputed pool");
            assertEquals(expected, pool.entries(), "group " + id + "'s pool drifted from the filter");
            int[] weights = new int[expected.size()];
            for (int i = 0; i < expected.size(); i++) weights[i] = expected.get(i).weight();
            assertArrayEquals(weights, pool.weights(), "group " + id + "'s weight column drifted");
        }
        assertNull(graph.poolFor(4), "a group with nothing but dead references has no pool");
    }

    @Test
    @DisplayName("a reference-free group's pool reuses the group's own list, no copy")
    void referenceFreePoolIsNotCopied() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        List<VariantState> pool = groupOnePool();
        sc.put(CELL_1, pool);
        sc.setLockId(CELL_1, 1);
        sc.put(CELL_2, List.of(of(Blocks.OAK_PLANKS), ref(1)));
        sc.setLockId(CELL_2, 2);

        assertSame(sc.groupRefs().statesForLockId(1), sc.groupRefs().graph().poolFor(1).entries(),
            "nothing needed dropping, so the pool must be the list itself");
    }

    @Test
    @DisplayName("sidecars with no references pick exactly as they did before v9")
    void noReferencesMeansNoBehaviourChange() {
        // The regression guard for every sidecar already on disk: the pick must
        // be bit-identical to the raw pickers, locked and unlocked alike.
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        List<VariantState> pool = groupOnePool();
        sc.put(CELL_1, pool);
        sc.setLockId(CELL_1, 1);
        sc.put(CELL_LOOSE, pool);

        int[] weights = { 1, 1, 1 };
        for (int seed = 0; seed < SEEDS; seed++) {
            for (int idx = 0; idx < INDICES; idx++) {
                assertSame(pool.get(CarriageVariantBlocks.pickIndexFromLockGroup(1, seed, idx, weights)),
                    sc.resolve(CELL_1, seed, idx), "locked pick drifted");
                assertSame(pool.get(CarriageVariantBlocks.pickIndexWeighted(CELL_LOOSE, seed, idx, pool)),
                    sc.resolve(CELL_LOOSE, seed, idx), "unlocked pick drifted");
            }
        }
    }

    @Test
    @DisplayName("the graph is recomputed after the sidecar changes underneath it")
    void graphCacheIsInvalidatedOnMutation() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_2, List.of(of(Blocks.OAK_PLANKS), ref(1)));
        sc.setLockId(CELL_2, 2);
        // Group 1 does not exist yet — the reference is dead, so oak every time.
        for (int idx = 0; idx < 8; idx++) {
            assertEquals(Blocks.OAK_PLANKS, blockOf(sc.resolve(CELL_2, 5L, idx)));
        }

        sc.put(CELL_1, groupOnePool());
        sc.setLockId(CELL_1, 1);

        Set<Block> seen = new HashSet<>();
        for (int idx = 0; idx < 64; idx++) {
            seen.add(blockOf(sc.resolve(CELL_2, 5L, idx)));
        }
        assertTrue(seen.stream().anyMatch(GROUP_ONE_BLOCKS::contains),
            "the reference stayed dead after its target group appeared — a stale cached graph");
    }

    @Test
    @DisplayName("reaches() sees straight, chained and absent paths for the editor's loop guard")
    void reachesBacksTheAuthorTimeGuard() {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        sc.put(CELL_1, groupOnePool());
        sc.setLockId(CELL_1, 1);
        sc.put(CELL_2, List.of(of(Blocks.OAK_PLANKS), ref(1)));
        sc.setLockId(CELL_2, 2);
        sc.put(CELL_3, List.of(of(Blocks.GLASS), ref(2)));
        sc.setLockId(CELL_3, 3);

        VariantGroupRefs.LockGroupView view = sc.groupRefs();
        assertTrue(VariantGroupRefs.reaches(view, 2, 1), "direct edge 2 → 1");
        assertTrue(VariantGroupRefs.reaches(view, 3, 1), "chained 3 → 2 → 1");
        assertTrue(VariantGroupRefs.reaches(view, 1, 1), "a group trivially reaches itself");
        assertFalse(VariantGroupRefs.reaches(view, 1, 3), "group 1 references nothing");
    }

    @Test
    @DisplayName("groupRef survives the v9 JSON round-trip and v8 entries stay diff-clean")
    void jsonRoundTrip() {
        StringBuilder sb = new StringBuilder();
        CarriageVariantBlocks.appendVariantJson(sb, ref(3).withWeight(4));
        String json = sb.toString();
        assertTrue(json.contains("\"groupRef\": 3"), "groupRef missing from JSON: " + json);
        assertTrue(json.contains("\"weight\": 4"), "weight missing from JSON: " + json);

        StringBuilder plain = new StringBuilder();
        CarriageVariantBlocks.appendVariantJson(plain, of(Blocks.STONE));
        assertEquals("\"minecraft:stone\"", plain.toString(),
            "a reference-free entry must still serialise as the v1 bare string");

        List<VariantState> parsed = new ArrayList<>();
        parsed.add(CarriageVariantBlocks.parseVariantElement(
            com.google.gson.JsonParser.parseString(json),
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),
            "test", CELL_1));
        assertEquals(3, parsed.get(0).groupRef(), "groupRef did not survive the round-trip");
        assertEquals(4, parsed.get(0).weight());
    }
}
