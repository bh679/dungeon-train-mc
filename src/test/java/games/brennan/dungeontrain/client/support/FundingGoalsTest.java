package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.net.relay.DonationSummaryClient.Goal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import games.brennan.dungeontrain.RepoPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which rung of the relay's support ladder the death screen puts in front of the player, and which
 * ones it ticks off above the grid. The rendering itself needs a client; everything that decides
 * WHAT is rendered lives in {@link FundingGoals} and is covered here.
 */
final class FundingGoalsTest {

    private static Goal goal(String id, int percent, boolean complete) {
        return new Goal(id, id + " label", 100, percent, percent, complete);
    }

    private static final Goal COSTS_DONE = goal("running_costs", 100, true);
    private static final Goal COSTS_PART = goal("running_costs", 40, false);
    private static final Goal TOOLS_PART = goal("dev_tools", 25, false);
    private static final Goal TOOLS_DONE = goal("dev_tools", 100, true);

    // ---- which goal is the ask -----------------------------------------------------------

    @Test
    @DisplayName("no ladder at all — an older relay, or no cost snapshot — yields no active goal")
    void emptyLadderHasNoActiveGoal() {
        assertNull(FundingGoals.active(List.of(), "dev_tools"));
        assertNull(FundingGoals.active(null, null));
        assertTrue(FundingGoals.completed(List.of(), null).isEmpty());
        assertTrue(FundingGoals.completed(null, null).isEmpty());
    }

    @Test
    @DisplayName("the relay's own choice of active goal wins")
    void relayActiveIdWins() {
        List<Goal> ladder = List.of(COSTS_DONE, TOOLS_PART);
        assertEquals("dev_tools", FundingGoals.active(ladder, "dev_tools").id());
    }

    @Test
    @DisplayName("an unknown or missing activeGoalId falls back to the first incomplete rung")
    void unknownActiveIdFallsBackToFirstIncomplete() {
        List<Goal> ladder = List.of(COSTS_DONE, TOOLS_PART);
        // A relay that added a third goal this jar's payload didn't carry, or sent nothing at all.
        assertEquals("dev_tools", FundingGoals.active(ladder, "something_new").id());
        assertEquals("dev_tools", FundingGoals.active(ladder, null).id());
        assertEquals("dev_tools", FundingGoals.active(ladder, "  ").id());
        assertEquals("running_costs", FundingGoals.active(List.of(COSTS_PART, TOOLS_PART), null).id());
    }

    @Test
    @DisplayName("every goal funded still shows the last rung rather than going blank")
    void everythingFundedShowsTheLastRung() {
        assertEquals("dev_tools", FundingGoals.active(List.of(COSTS_DONE, TOOLS_DONE), null).id());
    }

    // ---- which goals are ticked off ------------------------------------------------------

    @Test
    @DisplayName("goals the grid already tiles are not also ticked off above it")
    void completedExcludesTiledGoals() {
        // The standard two-rung ladder, both funded: dev_tools holds the goal tile and
        // running_costs holds the blue server-costs tile, so the ✓ line has nothing left to say.
        List<Goal> all = List.of(COSTS_DONE, TOOLS_DONE);
        assertTrue(FundingGoals.completed(all, List.of("dev_tools", FundingGoals.RUNNING_COSTS)).isEmpty());
    }

    @Test
    @DisplayName("nothing is ticked off until the first goal is actually covered")
    void nothingCompletedEarlyInTheMonth() {
        assertTrue(FundingGoals.completed(List.of(COSTS_PART, TOOLS_PART), List.of()).isEmpty());
    }

    @Test
    @DisplayName("a funded rung the grid has no slot for is ticked off, in ladder order")
    void completedKeepsLadderOrderForUntiledGoals() {
        // A third rung the relay added: dev_tools is now funded but no longer tiled, so it
        // reappears on the ✓ line above the grid.
        Goal third = goal("third", 10, false);
        List<Goal> ladder = List.of(COSTS_DONE, TOOLS_DONE, third);
        assertEquals(List.of("dev_tools"),
                FundingGoals.completed(ladder, List.of("third", FundingGoals.RUNNING_COSTS))
                        .stream().map(Goal::id).toList());
    }

    // ---- the server-costs rung the grid tiles specifically --------------------------------

    @Test
    @DisplayName("the running-costs rung is findable by id, and absent ids give null")
    void findsTheRunningCostsRung() {
        List<Goal> ladder = List.of(COSTS_DONE, TOOLS_PART);
        assertEquals(100, FundingGoals.byId(ladder, FundingGoals.RUNNING_COSTS).percent());
        assertNull(FundingGoals.byId(ladder, "sound_design"));
        assertNull(FundingGoals.byId(ladder, null));
        assertNull(FundingGoals.byId(null, FundingGoals.RUNNING_COSTS));
    }

    // ---- naming --------------------------------------------------------------------------

    @Test
    @DisplayName("a goal id maps to its translation key")
    void labelKeyIsDerivedFromTheId() {
        assertEquals("gui.dungeontrain.death.narr.goal_dev_tools", FundingGoals.labelKey("dev_tools"));
        assertEquals("gui.dungeontrain.death.narr.goal_dev_tools_done",
                FundingGoals.doneLabelKey("dev_tools"));
    }

    @Test
    @DisplayName("the recurring bill names its period on the ticked-off line, where no figure sits beside it")
    void runningCostsHasASettledLineString() throws Exception {
        // Ticked off with no period named, a RECURRING bill reads as settled for good. Every other
        // rung is a one-off and correctly falls back to its ordinary label, so this is the one id
        // the string has to exist for — a fallback here would be a wrong sentence, not a missing one.
        String en = Files.readString(RepoPaths.root().resolve(
                "src/main/resources/assets/dungeontrain/lang/en_us.json"), StandardCharsets.UTF_8);
        assertTrue(en.contains('"' + FundingGoals.doneLabelKey(FundingGoals.RUNNING_COSTS) + '"'),
                "en_us.json must carry the running-costs settled-line string");
    }
}
