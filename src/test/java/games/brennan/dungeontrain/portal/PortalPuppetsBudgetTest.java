package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.portal.PortalPuppets.Candidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a crowded corridor spends its puppet slots.
 *
 * <p>A player reported clearing a horde in a portal room and watching the zombies go invisible
 * while they were still being hit. Two things did that. The room may hold
 * {@link PortalRoomMobs#MAX_LIVE_PER_STRUCTURE} mobs and only sixteen of them could be drawn — and
 * the budget was open to <i>every</i> entity, so the rotten flesh and experience orbs the fight
 * itself dropped took slots from the mobs still swinging. On top of that, which sixteen won came
 * down to the level's own scan order, which reshuffles every tick, so the survivors churned
 * instead of holding still.</p>
 *
 * <p>The cap has since been raised past an authored room, so the ranking only bites in a corridor
 * somebody has over-filled by hand; the tests below pin the ranking against the old sixteen-slot
 * budget, where the policy is easiest to see, and pin the real cap against the room separately.
 * {@link PortalPuppets#select} takes plain values rather than entities so all of this is testable
 * without a level to stand them in.</p>
 */
class PortalPuppetsBudgetTest {

    /** The budget the ranking was designed under: small enough that a horde overflows it. */
    private static final int TIGHT = 16;

    private static Candidate at(int id, int tier, double distSq) {
        return new Candidate(id, id, tier, distSq);
    }

    private static List<Integer> idsOf(List<Candidate> chosen) {
        List<Integer> ids = new ArrayList<>();
        for (Candidate c : chosen) ids.add(c.id());
        return ids;
    }

    @Test
    @DisplayName("Loot never takes a slot a mob wanted")
    void livingBeatsScenery() {
        List<Candidate> candidates = new ArrayList<>();
        // The reported shape: a full horde, and more loot on the floor than there are slots.
        for (int i = 0; i < 64; i++) candidates.add(at(100 + i, PortalPuppets.TIER_LIVING, i));
        for (int i = 0; i < 40; i++) candidates.add(at(500 + i, PortalPuppets.TIER_SCENERY, 0.5));

        List<Candidate> chosen = PortalPuppets.select(candidates, TIGHT);

        assertEquals(TIGHT, chosen.size());
        for (Candidate c : chosen) {
            assertEquals(PortalPuppets.TIER_LIVING, c.tier(),
                "loot displaced a mob even though the corridor was full of mobs");
        }
    }

    @Test
    @DisplayName("Loot is still drawn once the mobs have stopped asking")
    void sceneryFillsWhatIsLeft() {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 3; i++) candidates.add(at(100 + i, PortalPuppets.TIER_LIVING, i));
        for (int i = 0; i < 10; i++) candidates.add(at(500 + i, PortalPuppets.TIER_SCENERY, i));

        List<Candidate> chosen = PortalPuppets.select(candidates, PortalPuppets.MAX_PER_PAIR);

        assertEquals(13, chosen.size(), "an uncrowded corridor should draw everything in it");
    }

    @Test
    @DisplayName("A player outranks a full corridor of mobs")
    void playerAlwaysSurvivesTheBudget() {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 64; i++) candidates.add(at(100 + i, PortalPuppets.TIER_LIVING, 0.1));
        // Far away, and added last — neither should matter.
        candidates.add(at(9, PortalPuppets.TIER_PLAYER, 250.0));

        List<Candidate> chosen = PortalPuppets.select(candidates, TIGHT);

        assertEquals(PortalPuppets.TIER_PLAYER, chosen.get(0).tier());
        assertEquals(9, chosen.get(0).id(),
            "the puppet the feature exists for was dropped for scenery-tier company");
    }

    @Test
    @DisplayName("Within a tier the nearest are the ones drawn")
    void nearestWinWithinATier() {
        List<Candidate> candidates = new ArrayList<>();
        // Ids ascend as distance descends, so an implementation that kept insertion order or
        // sorted by id alone would keep the far ones and fail this.
        for (int i = 0; i < 40; i++) candidates.add(at(100 + i, PortalPuppets.TIER_LIVING, 40 - i));

        List<Candidate> chosen = PortalPuppets.select(candidates, TIGHT);

        double worstDrawn = 0;
        for (Candidate c : chosen) worstDrawn = Math.max(worstDrawn, c.distSq());
        assertTrue(worstDrawn <= 16.0,
            "the sixteen drawn should be the sixteen nearest, but one was " + worstDrawn + " away");
    }

    @Test
    @DisplayName("The same room yields the same sixteen however the level reported it")
    void selectionIsStableAcrossScanOrder() {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            // Deliberately tied on distance, so only the id tiebreak can settle the order. This is
            // the churn regression: without it, a reshuffled scan order re-picked every tick and
            // each dropped puppet cost a render model rebuilt on the render thread.
            candidates.add(at(100 + i, PortalPuppets.TIER_LIVING, 4.0));
        }

        List<Integer> first = idsOf(PortalPuppets.select(candidates, TIGHT));

        List<Candidate> reshuffled = new ArrayList<>(candidates);
        Collections.shuffle(reshuffled, new java.util.Random(1234));
        List<Integer> second = idsOf(PortalPuppets.select(reshuffled, TIGHT));

        assertEquals(first, second, "the drawn set moved when only the scan order had changed");
    }

    @Test
    @DisplayName("Ranking never returns more than the budget, and never mutates its input")
    void boundedAndNonDestructive() {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 200; i++) candidates.add(at(100 + i, PortalPuppets.TIER_SCENERY, 200 - i));
        Candidate firstBefore = candidates.get(0);

        List<Candidate> chosen = PortalPuppets.select(candidates, PortalPuppets.MAX_PER_PAIR);

        assertEquals(PortalPuppets.MAX_PER_PAIR, chosen.size());
        assertEquals(200, candidates.size());
        assertSame(firstBefore, candidates.get(0),
            "select reordered the caller's list, which gather still indexes into");
    }

    @Test
    @DisplayName("The real cap draws an authored room whole — every mob, plus the players fighting them")
    void capCoversAnAuthoredRoom() {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < PortalRoomMobs.MAX_LIVE_PER_STRUCTURE; i++) {
            candidates.add(at(100 + i, PortalPuppets.TIER_LIVING, i));
        }
        for (int i = 0; i < 4; i++) candidates.add(at(1 + i, PortalPuppets.TIER_PLAYER, 1.0));

        List<Candidate> chosen = PortalPuppets.select(candidates, PortalPuppets.MAX_PER_PAIR);

        assertEquals(candidates.size(), chosen.size(),
            "the cap is below what a room is allowed to hold, so some of its mobs are invisible");
    }

    @Test
    @DisplayName("An empty corridor ranks to nothing rather than failing")
    void emptyIsFine() {
        assertEquals(0, PortalPuppets.select(new ArrayList<>(), PortalPuppets.MAX_PER_PAIR).size());
    }
}
