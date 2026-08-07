package games.brennan.dungeontrain.client.support;

import games.brennan.dungeontrain.net.relay.DonationSummaryClient.Goal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("completed goals exclude the one currently on display")
    void completedExcludesTheActiveGoal() {
        // Every rung funded: dev_tools is still the ask, so ticking it off above the grid too
        // would show the same goal twice.
        List<Goal> all = List.of(COSTS_DONE, TOOLS_DONE);
        assertEquals(List.of("running_costs"),
                FundingGoals.completed(all, null).stream().map(Goal::id).toList());
    }

    @Test
    @DisplayName("nothing is ticked off until the first goal is actually covered")
    void nothingCompletedEarlyInTheMonth() {
        assertTrue(FundingGoals.completed(List.of(COSTS_PART, TOOLS_PART), null).isEmpty());
    }

    @Test
    @DisplayName("completed goals keep the relay's ladder order")
    void completedKeepsLadderOrder() {
        Goal third = goal("third", 10, false);
        List<Goal> ladder = List.of(COSTS_DONE, TOOLS_DONE, third);
        assertEquals(List.of("running_costs", "dev_tools"),
                FundingGoals.completed(ladder, "third").stream().map(Goal::id).toList());
    }

    // ---- naming --------------------------------------------------------------------------

    @Test
    @DisplayName("a goal id maps to its translation key")
    void labelKeyIsDerivedFromTheId() {
        assertEquals("gui.dungeontrain.death.narr.goal_dev_tools", FundingGoals.labelKey("dev_tools"));
    }
}
