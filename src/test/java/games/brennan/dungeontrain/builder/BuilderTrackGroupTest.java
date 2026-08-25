package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.variant.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The menu's reading of {@link TrackKind}.
 *
 * <p>The property worth defending is coverage: {@link TrackKind} is a storage enum that grows when
 * a new track-side template kind is added, and a kind that belongs to no group is a template a
 * builder can never reach through the Open screen. The tests below fail the moment that happens.</p>
 */
final class BuilderTrackGroupTest {

    @Test
    @DisplayName("Every track kind is listed under exactly one group")
    void everyKindHasExactlyOneHome() {
        List<TrackKind> listed = new ArrayList<>();
        for (BuilderTrackGroup group : BuilderTrackGroup.values()) {
            listed.addAll(group.kinds());
        }
        // Track-side kinds only: PORTAL_ROOM is a TrackKind because that is where its files live,
        // and is opened through Train Dimensions as its own volume rather than off a plot.
        List<TrackKind> trackSide = new ArrayList<>();
        for (TrackKind kind : TrackKind.values()) {
            if (BuilderTrackPlot.isTrackSide(kind)) {
                trackSide.add(kind);
            }
        }
        assertEquals(trackSide.size(), listed.size(), "a kind is listed twice, or not at all");
        for (TrackKind kind : trackSide) {
            assertTrue(listed.contains(kind), kind.id() + " is in no group — unreachable in Open");
        }
    }

    @Test
    @DisplayName("of() answers the group each kind was listed under")
    void ofAgreesWithTheLists() {
        for (BuilderTrackGroup group : BuilderTrackGroup.values()) {
            for (TrackKind kind : group.kinds()) {
                assertEquals(group, BuilderTrackGroup.of(kind), kind.id());
            }
        }
    }

    @Test
    @DisplayName("Only Tracks holds a single kind — the one group that skips the kind level")
    void tracksIsTheSingleKindGroup() {
        assertTrue(BuilderTrackGroup.TRACKS.isSingleKind());
        assertEquals(TrackKind.TILE, BuilderTrackGroup.TRACKS.soleKind());

        assertFalse(BuilderTrackGroup.PILLARS.isSingleKind());
        assertFalse(BuilderTrackGroup.TUNNELS.isSingleKind());
        assertFalse(BuilderTrackGroup.STAIRS.isSingleKind());
    }

    @Test
    @DisplayName("The groups are in menu order — these are the Open grid's first four tiles")
    void declarationOrderIsMenuOrder() {
        // Deliberate rather than incidental: values() feeds the grid directly, so reordering the
        // enum reorders what a builder sees. Tracks first because it is the thing most often opened.
        assertEquals(
                List.of(BuilderTrackGroup.TRACKS, BuilderTrackGroup.TUNNELS,
                        BuilderTrackGroup.STAIRS, BuilderTrackGroup.PILLARS),
                List.of(BuilderTrackGroup.values()));
    }

    @Test
    @DisplayName("Pillars read ground-up, the order they stack in the world")
    void pillarsAreOrderedGroundUp() {
        assertEquals(
                List.of(TrackKind.PILLAR_BOTTOM, TrackKind.PILLAR_MIDDLE, TrackKind.PILLAR_TOP),
                BuilderTrackGroup.PILLARS.kinds());
    }

    @Test
    @DisplayName("Ids round-trip, and unknown ones are simply absent")
    void idsRoundTrip() {
        for (BuilderTrackGroup group : BuilderTrackGroup.values()) {
            assertEquals(group, BuilderTrackGroup.fromId(group.id()).orElse(null));
            assertEquals(group, BuilderTrackGroup.fromId(group.id().toUpperCase()).orElse(null));
        }
        assertTrue(BuilderTrackGroup.fromId("carriages").isEmpty());
        assertTrue(BuilderTrackGroup.fromId(null).isEmpty());
    }

    @Test
    @DisplayName("Every group has a label key and a non-empty kind list")
    void groupsAreWellFormed() {
        for (BuilderTrackGroup group : BuilderTrackGroup.values()) {
            assertNotNull(group.soleKind(), group.id() + " has no kinds at all");
            assertEquals("gui.dungeontrain.builder.track_group." + group.id(), group.labelKey());
        }
    }
}
