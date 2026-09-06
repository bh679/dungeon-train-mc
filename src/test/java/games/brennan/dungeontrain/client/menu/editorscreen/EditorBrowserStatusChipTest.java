package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.client.builder.BuilderProfileFilters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The browser's status chip: pressing it walks every review state and comes back to All.
 *
 * <p>Worth pinning because the chip is the only way to reach four of the five: a cycle that skipped
 * one would hide a whole slice of the queue with nothing on screen to say so, and a cycle that never
 * returned to {@link BuilderProfileFilters#ALL} would strand the reviewer inside a filter.</p>
 */
final class EditorBrowserStatusChipTest {

    @Test
    @DisplayName("the chip visits every state and wraps back to All")
    void cycleCoversEveryState() {
        List<String> seen = new ArrayList<>();
        String state = BuilderProfileFilters.ALL;
        for (int i = 0; i < 5; i++) {
            seen.add(state);
            state = EditorBrowserPane.nextStatus(state);
        }
        assertEquals(List.of(BuilderProfileFilters.ALL, BuilderReviewState.NONE,
                BuilderReviewState.SUBMITTED, BuilderReviewState.ACCEPTED, BuilderReviewState.DECLINED),
                seen, "funnel order: never asked, waiting, then decided");
        assertEquals(BuilderProfileFilters.ALL, state, "the sixth press is back to everything");
    }

    @Test
    @DisplayName("a state this version does not know starts the cycle over rather than sticking")
    void unknownStateFallsBackToAll() {
        assertEquals(BuilderProfileFilters.ALL, EditorBrowserPane.nextStatus("some_future_state"));
        assertEquals(BuilderProfileFilters.ALL, EditorBrowserPane.nextStatus(null));
    }

    @Test
    @DisplayName("every state the chip offers narrows to itself, and All narrows to nothing")
    void everyOfferedStateIsOneTheFilterKnows() {
        String state = EditorBrowserPane.nextStatus(BuilderProfileFilters.ALL);
        while (!BuilderProfileFilters.ALL.equals(state)) {
            String review = state;
            assertTrue(BuilderProfileFilters.matches(entry(review), BuilderProfileFilters.ALL, review),
                    "a build in state " + review + " must survive its own chip");
            state = EditorBrowserPane.nextStatus(state);
        }
    }

    private static games.brennan.dungeontrain.net.BuilderProfilePacket.Entry entry(String review) {
        return new games.brennan.dungeontrain.net.BuilderProfilePacket.Entry(1, "carriage", "", "cabin",
                false, "", BuilderReviewState.NONE.equals(review) ? "" : review, "", 0, false, "u", "n");
    }
}
