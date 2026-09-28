package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.builder.BuilderTemplateIdentity.Identity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which template a builder world is holding.
 *
 * <p>The interesting cases are the two that a reading of the sub type alone gets wrong: a Carriage
 * Room browsed from outside the wall is a <em>carriage</em>, and a Whole Carriage build with more
 * than one carriage parked is a <em>group</em>. Both are silent failures if resolved the other way —
 * a byline filed against a name in the wrong store simply never appears.</p>
 */
final class BuilderTemplateIdentityTest {

    private static Optional<Identity> of(String modeId, String subTypeId, String partKindId,
                                         String trackKindId, String name, int parked) {
        return BuilderTemplateIdentity.of(modeId, subTypeId, partKindId, trackKindId, name, parked);
    }

    @Test
    @DisplayName("A draft names no template")
    void draftIsNothing() {
        assertTrue(of("train_outside", "whole_carriage", "", "", "", 1).isEmpty());
    }

    @Test
    @DisplayName("One parked carriage is a carriage; more than one is the run they make together")
    void carriageOrGroup() {
        assertEquals(new Identity(BuilderPhotoPaths.Kind.CARRIAGE, "", "crate_car"),
                of("train_outside", "whole_carriage", "", "", "crate_car", 1).orElseThrow());
        assertEquals(new Identity(BuilderPhotoPaths.Kind.CARRIAGE_GROUP, "", "crate_car"),
                of("train_outside", "whole_carriage", "", "", "crate_car", 3).orElseThrow());
    }

    @Test
    @DisplayName("A Carriage Room is contents from inside and the carriage itself from outside")
    void roomDependsOnWhichSideOfTheWall() {
        assertEquals(new Identity(BuilderPhotoPaths.Kind.CONTENTS, "", "bunks"),
                of("inside_carriage", "carriage_room", "", "", "bunks", 1).orElseThrow());
        assertEquals(new Identity(BuilderPhotoPaths.Kind.CARRIAGE, "", "bunks"),
                of("train_outside", "carriage_room", "", "", "bunks", 1).orElseThrow());
    }

    @Test
    @DisplayName("A part carries the id-space it belongs to, and names nothing without one")
    void partNeedsItsKind() {
        assertEquals(new Identity(BuilderPhotoPaths.Kind.PART, "wall", "standard"),
                of("inside_carriage", "parts", "wall", "", "standard", 1).orElseThrow());
        assertTrue(of("inside_carriage", "parts", "", "", "standard", 1).isEmpty());
    }

    @Test
    @DisplayName("A track kind decides before the sub type gets a say")
    void trackWinsFirst() {
        Identity id = of("tracks_tunnels", "whole_carriage", "", "tunnel_section", "default", 1).orElseThrow();
        assertEquals(BuilderPhotoPaths.Kind.TRACK, id.kind());
        assertEquals("tunnel_section", id.subKind());
        assertEquals("default", id.id());
    }

    @Test
    @DisplayName("A portal room is its own kind, not a track kind and not a room's contents")
    void portalRoomIsItsOwnKind() {
        assertEquals(new Identity(BuilderPhotoPaths.Kind.PORTAL_ROOM, "", "library"),
                of("inside_carriage", BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE, "", "", "library", 1)
                        .orElseThrow());
    }

    @Test
    @DisplayName("An unrecognised sub type reads as a whole carriage, the way a save does")
    void unknownSubTypeFallsBack() {
        assertEquals(BuilderPhotoPaths.Kind.CARRIAGE,
                of("train_outside", "something_else", "", "", "crate_car", 1).orElseThrow().kind());
    }
}
