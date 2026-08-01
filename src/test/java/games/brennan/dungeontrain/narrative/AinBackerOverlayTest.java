package games.brennan.dungeontrain.narrative;

import games.brennan.adventureitemnames.api.NameSegment;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The segment-targeting logic that decides WHERE a backer name is injected into AdventureItemNames.
 *
 * <p>This is the most version-brittle part of the feature — an override is keyed by (chain id,
 * segment index), so anything that hardcoded indices would break silently on an AIN bump. These
 * tests pin the alternative: segments are found by the community-names ref they already carry.</p>
 */
class AinBackerOverlayTest {

    private static final ResourceLocation DISCORD =
            ResourceLocation.fromNamespaceAndPath("adventureitemnames", "discord_people");
    private static final ResourceLocation TITLES =
            ResourceLocation.fromNamespaceAndPath("adventureitemnames", "titles");
    private static final ResourceLocation BACKERS =
            ResourceLocation.fromNamespaceAndPath("dungeontrain", "backers");

    private static NameSegment seg(ResourceLocation... refs) {
        List<NameSegment.WeightedRef> weighted = new java.util.ArrayList<>();
        for (ResourceLocation r : refs) weighted.add(new NameSegment.WeightedRef(r, 58f));
        return new NameSegment(List.copyOf(weighted), 1f, " ", false);
    }

    // ---- targetSegments ---------------------------------------------------------

    @Test
    @DisplayName("finds the segment carrying the community-names ref")
    void findsMatchingSegment() {
        List<NameSegment> segments = List.of(seg(TITLES), seg(DISCORD), seg(TITLES));
        assertEquals(List.of(1), AinBackerOverlay.targetSegments(segments));
    }

    @Test
    @DisplayName("finds every matching segment, not just the first")
    void findsAllMatches() {
        // mob_name carries the pool on BOTH its First Name and Last Name segments.
        List<NameSegment> segments = List.of(seg(DISCORD), seg(TITLES), seg(DISCORD, TITLES));
        assertEquals(List.of(0, 2), AinBackerOverlay.targetSegments(segments));
    }

    @Test
    @DisplayName("the match tracks the segment when AIN reorders — this is why indices aren't hardcoded")
    void matchMovesWithReordering() {
        assertEquals(List.of(0), AinBackerOverlay.targetSegments(List.of(seg(DISCORD), seg(TITLES))));
        // Same chain, a segment inserted ahead of it by a later AIN release.
        assertEquals(List.of(1), AinBackerOverlay.targetSegments(List.of(seg(TITLES), seg(DISCORD))));
    }

    @Test
    @DisplayName("an AIN version that drops the pool from a chain yields no override at all")
    void noMatchIsANoOp() {
        assertTrue(AinBackerOverlay.targetSegments(List.of(seg(TITLES), seg(TITLES))).isEmpty());
        assertTrue(AinBackerOverlay.targetSegments(List.of()).isEmpty());
        assertTrue(AinBackerOverlay.targetSegments(null).isEmpty());
    }

    @Test
    @DisplayName("a segment with no refs is skipped rather than throwing")
    void tolerateEmptyRefs() {
        List<NameSegment> segments = List.of(seg(), seg(DISCORD));
        assertEquals(List.of(1), AinBackerOverlay.targetSegments(segments));
    }

    // ---- withRef ----------------------------------------------------------------

    @Test
    @DisplayName("withRef APPENDS, keeping every shipped ref")
    void withRefKeepsShipped() {
        // AIN's overrideSegment replaces the refs list wholesale, so dropping the shipped refs here
        // would silently delete every other name option in that segment.
        List<NameSegment.WeightedRef> shipped = seg(TITLES, DISCORD).refs();
        List<NameSegment.WeightedRef> out = AinBackerOverlay.withRef(shipped, BACKERS, 20f);

        assertEquals(3, out.size());
        assertEquals(TITLES, out.get(0).ref());
        assertEquals(DISCORD, out.get(1).ref());
        assertEquals(BACKERS, out.get(2).ref());
        assertEquals(20f, out.get(2).weight());
        assertEquals(58f, out.get(0).weight(), "shipped weights untouched");
    }

    @Test
    @DisplayName("withRef tolerates a null or empty shipped list")
    void withRefTolerantOfEmpty() {
        assertEquals(1, AinBackerOverlay.withRef(null, BACKERS, 20f).size());
        assertEquals(1, AinBackerOverlay.withRef(List.of(), BACKERS, 20f).size());
    }

    @Test
    @DisplayName("the backer pool ids are namespaced to dungeontrain, never to AIN")
    void poolIdsAreOurs() {
        // Injecting under AIN's own discord_people id would REPLACE its 65 shipped community names.
        assertEquals("dungeontrain", AinBackerOverlay.BACKERS_POOL.getNamespace());
        assertEquals("dungeontrain", AinBackerOverlay.BACKER_PHRASES_POOL.getNamespace());
    }

    @Test
    @DisplayName("names and phrases target different chains")
    void nameAndPhraseChainsAreDisjoint() {
        // A phrase is sentence-shaped and reads badly inside an item title that already carries
        // two or three other segments, so it goes to the lore/dialogue chains instead.
        for (ResourceLocation c : AinBackerOverlay.NAME_CHAINS) {
            assertTrue(!AinBackerOverlay.PHRASE_CHAINS.contains(c), c + " should not be in both lists");
        }
        assertTrue(!AinBackerOverlay.NAME_CHAINS.isEmpty());
        assertTrue(!AinBackerOverlay.PHRASE_CHAINS.isEmpty());
    }
}
