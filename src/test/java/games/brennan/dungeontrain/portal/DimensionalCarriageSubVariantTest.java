package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.editor.TrackVariantGroupStore;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.template.TemplateMeta;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolution coverage for dimensional carriage sub-variants — the second hop
 * {@link TrackVariantRegistry#pickName} takes when the name it drew has a group sidecar.
 *
 * <p>Groups are injected via {@link TrackVariantGroupStore#injectForTesting}, so nothing here
 * touches the filesystem or needs a Forge bootstrap.</p>
 */
final class DimensionalCarriageSubVariantTest {

    private static final TrackKind KIND = TrackKind.DIMENSIONAL_CARRIAGE;
    private static final int SEEDS = 300;

    @BeforeEach
    @AfterEach
    void cleanSlate() {
        TrackVariantRegistry.clear();
        TrackVariantGroupStore.clearCache();
        // Weights carry the per-room gate the top-level pool filters on; start from a blank table so
        // the shipped weights.json cannot decide these answers either.
        TrackVariantWeights.clear();
        // The mod ships a real group sidecar for 'default' (portals/dimensional_carriage/default.group.json), and
        // 'default' is in every pool as the synthetic fallback — so without this the classpath copy
        // would leak its members into these fixtures and the answers would depend on shipped
        // content. Injecting an empty group states the world each test wants.
        TrackVariantGroupStore.injectForTesting(KIND, TrackKind.DEFAULT_NAME, TrackVariantGroup.EMPTY);
    }

    private static TrackVariantGroup group(String... idsAndWeights) {
        List<TrackVariantGroup.Member> ms = new java.util.ArrayList<>();
        for (int i = 0; i < idsAndWeights.length; i += 2) {
            ms.add(new TrackVariantGroup.Member(idsAndWeights[i], Integer.parseInt(idsAndWeights[i + 1])));
        }
        return new TrackVariantGroup(ms);
    }

    /**
     * Every distinct name drawn over {@link #SEEDS} pairs, minus the synthetic {@code default} the
     * registry always keeps in the pool — these tests are about what the group layer contributes.
     */
    private static Set<String> namesPickedOver(long worldSeed, GateContext ctx) {
        Set<String> seen = new HashSet<>();
        for (int pair = 0; pair < SEEDS; pair++) {
            seen.add(TrackVariantRegistry.pickName(KIND, worldSeed, pair, ctx));
        }
        seen.remove(TrackKind.DEFAULT_NAME);
        return seen;
    }

    @Test
    @DisplayName("a parent's sub-variants are drawn alongside the parent itself")
    void resolvesThroughGroup() {
        TrackVariantRegistry.register(KIND, "library");
        TrackVariantRegistry.register(KIND, "library_tall");
        TrackVariantRegistry.register(KIND, "library_flooded");
        TrackVariantGroupStore.injectForTesting(KIND, "library",
            group("library_tall", "1", "library_flooded", "1"));

        Set<String> seen = namesPickedOver(1234L, null);
        assertTrue(seen.contains("library"), "the parent keeps its own share of the draw");
        assertTrue(seen.contains("library_tall"));
        assertTrue(seen.contains("library_flooded"));
    }

    @Test
    @DisplayName("a sub-variant never appears as a sibling of its own parent")
    void childrenAreNotTopLevel() {
        TrackVariantRegistry.register(KIND, "library");
        TrackVariantRegistry.register(KIND, "library_tall");
        // selfWeight 0 → the parent can only ever resolve to a member, so any 'library' in the
        // output would have to have come from the top-level pool.
        TrackVariantGroupStore.injectForTesting(KIND, "library",
            group("library_tall", "1").withSelfWeight(0));

        Set<String> seen = namesPickedOver(99L, null);
        assertFalse(seen.contains("library"), "parent resolves away when its self share is 0");
        assertTrue(seen.contains("library_tall"));
    }

    @Test
    @DisplayName("the same (seed, pair) always lands on the same room — a re-stamped pair does not re-roll")
    void deterministicAcrossRestamps() {
        TrackVariantRegistry.register(KIND, "library");
        TrackVariantRegistry.register(KIND, "library_tall");
        TrackVariantRegistry.register(KIND, "library_flooded");
        TrackVariantGroupStore.injectForTesting(KIND, "library",
            group("library_tall", "3", "library_flooded", "3"));

        for (int pair = 0; pair < 50; pair++) {
            String first = TrackVariantRegistry.pickName(KIND, 4242L, pair);
            for (int repeat = 0; repeat < 3; repeat++) {
                assertEquals(first, TrackVariantRegistry.pickName(KIND, 4242L, pair),
                    "pair " + pair + " must resolve to the same room every time it is stamped");
            }
        }
    }

    @Test
    @DisplayName("with no group defined, the pick is byte-identical to the pre-group behaviour")
    void noGroupLeavesPickUnchanged() {
        TrackVariantRegistry.register(KIND, "library");
        TrackVariantRegistry.register(KIND, "cavern");

        String[] before = new String[SEEDS];
        for (int pair = 0; pair < SEEDS; pair++) {
            before[pair] = TrackVariantRegistry.pickName(KIND, 7L, pair);
        }

        // A group on an unrelated kind must not consume anything from this kind's stream either.
        TrackVariantGroupStore.injectForTesting(TrackKind.TILE, "tile_parent", group("tile_child", "1"));
        for (int pair = 0; pair < SEEDS; pair++) {
            assertEquals(before[pair], TrackVariantRegistry.pickName(KIND, 7L, pair));
        }
    }

    @Test
    @DisplayName("a member gated out of this context falls back to the parent, never to a gated room")
    void gatedMembersAreFilteredOut() {
        TrackVariantRegistry.register(KIND, "library");
        TrackVariantRegistry.register(KIND, "library_nether");
        TrackVariantGroupStore.injectForTesting(KIND, "library", new TrackVariantGroup(1, List.of(
            new TrackVariantGroup.Member("library_nether", 50,
                new TemplateGate(0, TemplateGate.ALL, EnumSet.of(TrainPhase.NETHER)), List.of()))));

        GateContext overworld = new GateContext(1, TrainPhase.OVERWORLD);
        Set<String> seenOverworld = namesPickedOver(5L, overworld);
        assertEquals(Set.of("library"), seenOverworld,
            "a NETHER-only sub-variant must not be drawn in the overworld");

        GateContext nether = new GateContext(1, TrainPhase.NETHER);
        assertTrue(namesPickedOver(5L, nether).contains("library_nether"),
            "and must be drawn where its gate allows");
    }

    @Test
    @DisplayName("a room gated out of this context is dropped from the top-level pool")
    void gatedRoomsAreFilteredOut() {
        TrackVariantRegistry.register(KIND, "library");
        TrackVariantRegistry.register(KIND, "hellhall");
        TrackVariantWeights.injectForTesting(KIND, "library", TemplateMeta.of(1));
        TrackVariantWeights.injectForTesting(KIND, "hellhall", new TemplateMeta(1,
            new TemplateGate(0, TemplateGate.ALL, EnumSet.of(TrainPhase.NETHER))));

        assertEquals(Set.of("library"), namesPickedOver(31L, new GateContext(1, TrainPhase.OVERWORLD)),
            "a NETHER-only room must not be drawn in the overworld");
        assertTrue(namesPickedOver(31L, new GateContext(1, TrainPhase.NETHER)).contains("hellhall"),
            "and must be drawn where its gate allows");
    }

    @Test
    @DisplayName("when every room is gated out the pool falls back ungated — a pair always gets a room")
    void allGatedOutFallsBackToUngatedPool() {
        TrackVariantRegistry.register(KIND, "hellhall");
        TrackVariantWeights.injectForTesting(KIND, "hellhall", new TemplateMeta(1,
            new TemplateGate(0, TemplateGate.ALL, EnumSet.of(TrainPhase.NETHER))));
        // 'default' is in the pool too and is ungated, so gate it out as well to empty the pool.
        TrackVariantWeights.injectForTesting(KIND, TrackKind.DEFAULT_NAME, new TemplateMeta(1,
            new TemplateGate(0, TemplateGate.ALL, EnumSet.of(TrainPhase.NETHER))));

        for (int pair = 0; pair < 20; pair++) {
            String picked = TrackVariantRegistry.pickName(KIND, 77L, pair,
                new GateContext(1, TrainPhase.OVERWORLD));
            assertTrue("hellhall".equals(picked) || TrackKind.DEFAULT_NAME.equals(picked),
                "an emptied pool falls back to the ungated one rather than failing, got " + picked);
        }
    }

    @Test
    @DisplayName("a member that is not a registered room falls back to the parent")
    void unknownMemberFallsBackToParent() {
        TrackVariantRegistry.register(KIND, "library");
        TrackVariantGroupStore.injectForTesting(KIND, "library",
            group("deleted_room", "50").withSelfWeight(1));

        assertEquals(Set.of("library"), namesPickedOver(11L, null));
    }

    @Test
    @DisplayName("nesting is single-hop — a member with its own group resolves as a leaf")
    void nestedGroupIsTreatedAsLeaf() {
        TrackVariantRegistry.register(KIND, "library");
        TrackVariantRegistry.register(KIND, "library_tall");
        TrackVariantRegistry.register(KIND, "library_tall_lit");
        TrackVariantGroupStore.injectForTesting(KIND, "library",
            group("library_tall", "50").withSelfWeight(0));
        TrackVariantGroupStore.injectForTesting(KIND, "library_tall",
            group("library_tall_lit", "50").withSelfWeight(0));

        Set<String> seen = namesPickedOver(21L, null);
        assertEquals(Set.of("library_tall"), seen, "the second hop is not taken again");
        assertFalse(seen.contains("library_tall_lit"));
    }

    @Test
    @DisplayName("the store's reverse index answers which parent a sub-variant belongs to")
    void reverseIndex() {
        TrackVariantRegistry.register(KIND, "library");
        TrackVariantRegistry.register(KIND, "library_tall");
        TrackVariantGroupStore.injectForTesting(KIND, "library", group("library_tall", "1"));

        assertEquals("library", TrackVariantGroupStore.findParentOf(KIND, "library_tall").orElse(null));
        assertTrue(TrackVariantGroupStore.findParentOf(KIND, "library").isEmpty());
        assertEquals(Set.of("library_tall"), TrackVariantGroupStore.allChildIds(KIND));
        assertFalse(TrackVariantGroupStore.topLevelNames(KIND).contains("library_tall"));
        assertTrue(TrackVariantGroupStore.topLevelNames(KIND).contains("library"));
        assertNotEquals(TrackVariantRegistry.namesFor(KIND).size(),
            TrackVariantGroupStore.topLevelNames(KIND).size());
    }
}
