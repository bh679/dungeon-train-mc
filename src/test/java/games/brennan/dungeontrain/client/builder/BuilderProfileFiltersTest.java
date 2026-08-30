package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A filter that quietly drops a build is indistinguishable, to its author, from a build that never
 * uploaded — so what these pin is mostly that nothing falls through the gaps between the two axes.
 */
final class BuilderProfileFiltersTest {

    private static BuilderProfilePacket.Entry build(int id, String kind, String review) {
        return new BuilderProfilePacket.Entry(id, kind, "", "b" + id, false, "approved", review, "stone", 0);
    }

    private static final BuilderProfilePacket.Entry WAITING =
            build(1, BuilderRelayKinds.CARRIAGE, BuilderReviewState.SUBMITTED);
    private static final BuilderProfilePacket.Entry ACCEPTED =
            build(2, BuilderRelayKinds.CARRIAGE, BuilderReviewState.ACCEPTED);
    private static final BuilderProfilePacket.Entry ROOM =
            build(3, BuilderRelayKinds.PORTAL_ROOM, BuilderReviewState.NONE);
    private static final List<BuilderProfilePacket.Entry> ALL_BUILDS = List.of(WAITING, ACCEPTED, ROOM);

    @Test
    @DisplayName("both chips on All show the whole profile, and the same list object")
    void unfiltered() {
        // Same instance, not just an equal one: this is the common case, on a screen that re-runs it
        // on every rebuild, and copying a profile to change nothing about it is pure waste.
        assertSame(ALL_BUILDS, BuilderProfileFilters.apply(ALL_BUILDS,
                BuilderProfileFilters.ALL, BuilderProfileFilters.ALL));
    }

    @Test
    @DisplayName("the two axes narrow independently and then together")
    void narrows() {
        assertEquals(List.of(WAITING, ACCEPTED), BuilderProfileFilters.apply(ALL_BUILDS,
                BuilderRelayKinds.CARRIAGE, BuilderProfileFilters.ALL));
        assertEquals(List.of(ROOM), BuilderProfileFilters.apply(ALL_BUILDS,
                BuilderProfileFilters.ALL, BuilderReviewState.NONE));
        assertEquals(List.of(ACCEPTED), BuilderProfileFilters.apply(ALL_BUILDS,
                BuilderRelayKinds.CARRIAGE, BuilderReviewState.ACCEPTED));
        // A pair that matches nothing is an ordinary answer, not an error — the screen has a line for it.
        assertEquals(List.of(), BuilderProfileFilters.apply(ALL_BUILDS,
                BuilderRelayKinds.PORTAL_ROOM, BuilderReviewState.ACCEPTED));
    }

    @Test
    @DisplayName("order is the relay's order, which is newest-first")
    void keepsOrder() {
        List<BuilderProfilePacket.Entry> reversed = List.of(ROOM, ACCEPTED, WAITING);
        assertEquals(List.of(ACCEPTED, WAITING), BuilderProfileFilters.apply(reversed,
                BuilderRelayKinds.CARRIAGE, BuilderProfileFilters.ALL));
    }

    @Test
    @DisplayName("a review state this version doesn't know files under not-submitted, never nowhere")
    void unknownReviewIsNotLost() {
        BuilderProfilePacket.Entry odd = build(9, BuilderRelayKinds.CARRIAGE, "escalated");
        assertTrue(BuilderProfileFilters.matches(odd, BuilderProfileFilters.ALL, BuilderProfileFilters.ALL),
                "an unrecognised state must never hide a build from the unfiltered view");
        assertTrue(BuilderProfileFilters.matches(odd, BuilderProfileFilters.ALL, BuilderReviewState.NONE));
        assertFalse(BuilderProfileFilters.matches(odd, BuilderProfileFilters.ALL, BuilderReviewState.SUBMITTED));
    }
}
