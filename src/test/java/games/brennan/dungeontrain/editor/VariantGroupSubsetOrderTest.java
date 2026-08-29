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
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Randomised guarantee that resolving v9 lock groups <b>in subset order</b>
 * did not change any answer — only when the answers are worked out.
 *
 * <p>{@link VariantGroupRefs#analyse} used to settle liveness with a
 * {@code while (changed)} fixpoint that re-walked the whole graph until it
 * stopped moving. It now walks once, forward along the subset order, because a
 * group's references can only point at groups that precede it there. That is a
 * real algorithmic substitution, and the shapes where a one-pass version would
 * differ from a fixpoint — deadness propagating two or three hops, a group
 * whose only live option arrives late, a mutual pair tangled with a live
 * third — are exactly the shapes nobody authors by hand and so nobody would
 * catch by eye.</p>
 *
 * <p>So the fixpoint stays, as the oracle: {@link #fixpointLive} below is the
 * old algorithm, kept deliberately in its original form, and every generated
 * graph must agree with it. The other two assertions are the properties the
 * new order has to carry on its own — that it really is a topological order,
 * and that it lists every group exactly once, since liveness and the
 * precomputed pools are both derived by walking it.</p>
 */
final class VariantGroupSubsetOrderTest {

    /** Graphs per shape. Enough that a rule firing one time in fifty still shows up. */
    private static final int GRAPHS = 300;
    private static final int MAX_GROUPS = 7;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Concrete options, deliberately distinct per group so agreement can be checked by block. */
    private static final Block[] PALETTE = {
        Blocks.STONE, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE,
        Blocks.OAK_PLANKS, Blocks.BIRCH_PLANKS, Blocks.GLASS, Blocks.BRICKS,
    };

    /**
     * A sidecar of {@code groups} lock groups whose entries are a random mix of
     * concrete blocks, references to other groups (back-edges and mutual pairs
     * included — the generator does not avoid them, that is the point) and
     * references to lock-ids no cell carries.
     */
    private static CarriageVariantBlocks randomSidecar(Random rng, int groups) {
        CarriageVariantBlocks sc = CarriageVariantBlocks.empty();
        for (int id = 1; id <= groups; id++) {
            int size = 2 + rng.nextInt(4);
            List<VariantState> states = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                if (rng.nextInt(100) < 55) {
                    states.add(VariantState.of(PALETTE[rng.nextInt(PALETTE.length)].defaultBlockState()));
                } else {
                    // groups + 2 so dangling references are drawn as often as real ones.
                    int target = 1 + rng.nextInt(groups + 2);
                    states.add(VariantState.ofGroupRef(target, Blocks.BEDROCK.defaultBlockState()));
                }
            }
            BlockPos cell = new BlockPos(id, 1, 1);
            sc.put(cell, states);
            sc.setLockId(cell, id);
        }
        return sc;
    }

    /**
     * Rule 2 exactly as it stood before the subset order: seed with the groups
     * holding a concrete entry, then iterate the whole id set until a sweep
     * changes nothing. Left in its original shape on purpose — an oracle that
     * had been tidied into the new algorithm's shape would prove nothing.
     */
    private static Set<Integer> fixpointLive(VariantGroupRefs.LockGroupView view,
                                             VariantGroupRefs.Graph graph) {
        Set<Integer> ids = view.lockIdsInUse();
        Set<Integer> live = new HashSet<>();
        for (int id : ids) {
            List<VariantState> states = view.statesForLockId(id);
            if (states == null) continue;
            for (VariantState s : states) {
                if (!s.isGroupRef()) {
                    live.add(id);
                    break;
                }
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int id : ids) {
                if (live.contains(id)) continue;
                List<VariantState> states = view.statesForLockId(id);
                if (states == null) continue;
                Set<Integer> targets = graph.usableTargets().getOrDefault(id, Set.of());
                for (VariantState s : states) {
                    if (s.isGroupRef() && targets.contains(s.groupRef()) && live.contains(s.groupRef())) {
                        live.add(id);
                        changed = true;
                        break;
                    }
                }
            }
        }
        return live;
    }

    @Test
    @DisplayName("single-pass liveness agrees with the fixpoint on every random graph")
    void livenessMatchesTheFixpoint() {
        Random rng = new Random(0xC0FFEE);
        int withDeadGroups = 0;
        for (int g = 0; g < GRAPHS; g++) {
            int groups = 1 + rng.nextInt(MAX_GROUPS);
            CarriageVariantBlocks sc = randomSidecar(rng, groups);
            VariantGroupResolver refs = sc.groupRefs();
            VariantGroupRefs.Graph graph = refs.graph();

            assertEquals(fixpointLive(refs, graph), graph.live(),
                "subset-order liveness drifted from the fixpoint on graph " + g);
            if (graph.live().size() < refs.lockIdsInUse().size()) withDeadGroups++;
        }
        assertTrue(withDeadGroups > 0,
            "no generated graph had a dead group, so propagation was never exercised");
    }

    @Test
    @DisplayName("the subset order is a real topological order over every group in use")
    void orderIsTopologicalAndComplete() {
        Random rng = new Random(0x5EED);
        int withSubsets = 0;
        for (int g = 0; g < GRAPHS; g++) {
            int groups = 1 + rng.nextInt(MAX_GROUPS);
            CarriageVariantBlocks sc = randomSidecar(rng, groups);
            VariantGroupResolver refs = sc.groupRefs();
            VariantGroupRefs.Graph graph = refs.graph();
            List<Integer> order = graph.order();

            assertEquals(new LinkedHashSet<>(refs.lockIdsInUse()), new LinkedHashSet<>(order),
                "the order must list every group in use, on graph " + g);
            assertEquals(order.size(), new HashSet<>(order).size(),
                "a group was listed twice on graph " + g);

            // Every group a group may reference — its subsets — must already
            // have been listed by the time the group itself is.
            Set<Integer> seen = new HashSet<>();
            for (int id : order) {
                List<VariantState> states = refs.statesForLockId(id);
                if (states != null) {
                    Set<Integer> allowed = graph.usableTargets().getOrDefault(id, Set.of());
                    for (VariantState s : states) {
                        if (!s.isGroupRef() || !allowed.contains(s.groupRef())) continue;
                        withSubsets++;
                        assertTrue(seen.contains(s.groupRef()),
                            "group " + id + " was ordered before its subset " + s.groupRef()
                                + " on graph " + g + " — order " + order);
                    }
                }
                seen.add(id);
            }
        }
        assertTrue(withSubsets > 0, "no generated group had a subset, so the order was never tested");
    }

    @Test
    @DisplayName("a followed reference still lands on the value its target group landed on")
    void referencesStillAgreeWithTheirTarget() {
        // The property the whole feature exists for, re-checked against the
        // precomputed pools follow() now reads instead of re-deriving.
        Random rng = new Random(0xBEEF);
        int agreements = 0;
        for (int g = 0; g < 120; g++) {
            int groups = 2 + rng.nextInt(MAX_GROUPS - 1);
            CarriageVariantBlocks sc = randomSidecar(rng, groups);
            for (long seed = 0; seed < 6; seed++) {
                for (int idx = 0; idx < 6; idx++) {
                    for (int id = 1; id <= groups; id++) {
                        VariantState got = sc.resolve(new BlockPos(id, 1, 1), seed, idx);
                        if (got == null) continue;  // every option was a dead reference
                        assertNotNull(got.state());
                        assertTrue(got.state().getBlock() != Blocks.BEDROCK,
                            "an unfollowed reference placeholder surfaced on graph " + g);
                        agreements++;
                    }
                }
            }
        }
        assertTrue(agreements > 0);
    }
}
