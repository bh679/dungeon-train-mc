package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriagePartKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The identity an Open carries to the server.
 *
 * <p>This record is the reason Open needs its own packet: {@code builderName} ends up pointing at
 * whatever it names, so an id that is ambiguous, empty, or missing its part kind must fail to build
 * a request rather than build a request that resolves to something else.</p>
 */
final class BuilderOpenRequestTest {

    @Test
    @DisplayName("Each sub type maps to the store that owns it")
    void subTypesMapToStores() {
        assertEquals(BuilderPhotoPaths.Kind.CARRIAGE,
                request(BuilderNewOptions.SubType.WHOLE_CARRIAGE, "desert", null).kind());
        assertEquals(BuilderPhotoPaths.Kind.CONTENTS,
                request(BuilderNewOptions.SubType.CARRIAGE_ROOM, "mess_hall", null).kind());
        assertEquals(BuilderPhotoPaths.Kind.PART,
                request(BuilderNewOptions.SubType.PARTS, "slatted", CarriagePartKind.FLOOR).kind());
    }

    @Test
    @DisplayName("The sub type survives the round trip, so Save writes the right kind of thing")
    void subTypeRoundTrips() {
        for (BuilderNewOptions.SubType subType : BuilderNewOptions.SubType.values()) {
            CarriagePartKind partKind = subType == BuilderNewOptions.SubType.PARTS
                    ? CarriagePartKind.WALLS
                    : null;
            assertEquals(subType.id(), request(subType, "thing", partKind).subTypeToken(),
                    "sub type did not survive for " + subType.id());
        }
    }

    @Test
    @DisplayName("A part with no kind is no request at all")
    void partWithoutKindIsRejected() {
        // `standard` exists as a floor and as a door. Without the kind the id names two files, and
        // guessing which would mean opening one and saving over the other.
        assertTrue(BuilderOpenRequest
                .forSelection(BuilderNewOptions.SubType.PARTS, "standard", null)
                .isEmpty());
    }

    @Test
    @DisplayName("A missing id is no request at all")
    void emptyIdIsRejected() {
        for (String id : new String[] {null, ""}) {
            assertTrue(BuilderOpenRequest
                            .forSelection(BuilderNewOptions.SubType.WHOLE_CARRIAGE, id, null)
                            .isEmpty(),
                    "an empty id must not produce a request");
        }
        assertTrue(BuilderOpenRequest.forSelection(null, "desert", null).isEmpty());
    }

    @Test
    @DisplayName("The part kind travels as its id, and as nothing when there isn't one")
    void partKindIdIsWireSafe() {
        assertEquals(CarriagePartKind.ROOF.id(),
                request(BuilderNewOptions.SubType.PARTS, "peaked", CarriagePartKind.ROOF).partKindId());
        // Empty rather than null: the packet writes this straight to the buffer.
        assertEquals("", request(BuilderNewOptions.SubType.WHOLE_CARRIAGE, "desert", null).partKindId());
    }

    @Test
    @DisplayName("A null id normalises rather than escaping as null")
    void nullIdNormalises() {
        BuilderOpenRequest direct = new BuilderOpenRequest(BuilderPhotoPaths.Kind.CARRIAGE, null, null);
        assertEquals("", direct.id());
        assertTrue(direct.isEmpty());
    }

    // ---- portal rooms: the one kind no sub type names ----

    @Test
    @DisplayName("A portal room request carries its own kind and token")
    void portalRoomHasItsOwnKindAndToken() {
        BuilderOpenRequest request = BuilderOpenRequest.forPortalRoom("labrynth");
        assertEquals(BuilderPhotoPaths.Kind.PORTAL_ROOM, request.kind());
        assertEquals("labrynth", request.id());
        assertEquals(BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE, request.subTypeToken());
        assertTrue(request.isPortalRoom());
        assertEquals("", request.partKindId(), "a room has no part kind to send");
    }

    @Test
    @DisplayName("The portal room token is not one of New's sub types")
    void portalRoomTokenIsNotASubType() {
        // If it ever collided with one, BuilderSave would take a carriage arm for a room and write
        // the room's blocks over a carriage template.
        for (BuilderNewOptions.SubType subType : BuilderNewOptions.SubType.values()) {
            assertFalse(subType.id().equals(BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE),
                    "sub type " + subType + " collides with the portal room token");
        }
    }

    @Test
    @DisplayName("Everything that isn't a room says so")
    void onlyRoomsAreRooms() {
        assertFalse(request(BuilderNewOptions.SubType.WHOLE_CARRIAGE, "desert", null).isPortalRoom());
        assertFalse(request(BuilderNewOptions.SubType.CARRIAGE_ROOM, "mess_hall", null).isPortalRoom());
    }

    @Test
    @DisplayName("A request's sub type is the store it saves to, never how much train to park")
    void requestSubTypeIsNotTheParkedCount() {
        // A carriage browsed under a Stage in the Carriage Room arm. The request has to say
        // WHOLE_CARRIAGE — BuilderSave reads it to pick which store to write, and this really is a
        // carriage template — while the builder opened exactly one of them.
        BuilderOpenRequest opened =
                new BuilderOpenRequest(BuilderPhotoPaths.Kind.CARRIAGE, "windowed", null);
        assertEquals(BuilderNewOptions.SubType.WHOLE_CARRIAGE, opened.subType());

        // So deriving the count from the request parks the whole run — which is the bug this
        // separation exists to prevent. The arm the tile was clicked in is the one that decides.
        assertEquals(3, BuilderWorldLayout.parkedCarriages(
                BuilderMode.TRAIN_OUTSIDE, opened.subType()));
        assertEquals(1, BuilderWorldLayout.parkedCarriages(
                BuilderMode.TRAIN_OUTSIDE, BuilderNewOptions.SubType.CARRIAGE_ROOM));
    }

    @Test
    @DisplayName("What a world records round-trips back to the request that put it there")
    void worldDataRoundTripsToTheSameRequest() {
        // Reopening a builder world re-runs the Open the world says it is holding, so this mapping
        // is what decides which store the reopened build — and the Save after it — belongs to.
        for (BuilderNewOptions.SubType subType : BuilderNewOptions.SubType.values()) {
            CarriagePartKind partKind = subType == BuilderNewOptions.SubType.PARTS
                    ? CarriagePartKind.WALLS
                    : null;
            BuilderOpenRequest opened = request(subType, "thing", partKind);
            BuilderOpenRequest reread = fromWorld(opened.id(), opened.subTypeToken(),
                    opened.partKindId(), opened.trackKindId());
            assertEquals(opened, reread, "did not survive the world for " + subType.id());
        }
    }

    @Test
    @DisplayName("A recorded room is a room and a recorded track is a track, not a carriage")
    void worldDataKeepsTheKindsApart() {
        BuilderOpenRequest room = BuilderOpenRequest.forPortalRoom("library");
        assertEquals(room, fromWorld("library", room.subTypeToken(), "", ""));

        // A track build records no sub type at all — the track kind is the field that names it — so
        // the kind must be read before the sub-type token, or a track reopens as a carriage.
        BuilderOpenRequest track = BuilderOpenRequest.forTrack(TrackKind.TILE, "mossy").orElseThrow();
        assertEquals(track, fromWorld("mossy", "", "", TrackKind.TILE.id()));
    }

    @Test
    @DisplayName("A world holding nothing named yields no request")
    void draftYieldsNothing() {
        // A New that was never saved has no name, so there is no template to reopen from — the
        // caller stands the mode's bare scene back up instead of guessing at one.
        assertTrue(BuilderOpenRequest.fromWorldData("", "carriage_room", "", "").isEmpty());
        assertTrue(BuilderOpenRequest.fromWorldData(null, "carriage_room", "", "").isEmpty());
        // And an unreadable record is a refusal, not a fallback to some other kind.
        assertTrue(BuilderOpenRequest.fromWorldData("thing", "not_a_sub_type", "", "").isEmpty());
        assertTrue(BuilderOpenRequest.fromWorldData("thing", "parts", "not_a_part", "").isEmpty());
    }

    private static BuilderOpenRequest fromWorld(String name, String subTypeToken, String partKindId,
                                                String trackKindId) {
        Optional<BuilderOpenRequest> built =
                BuilderOpenRequest.fromWorldData(name, subTypeToken, partKindId, trackKindId);
        assertTrue(built.isPresent(), "expected a request for recorded '" + name + "'");
        return built.get();
    }

    private static BuilderOpenRequest request(BuilderNewOptions.SubType subType, String id,
                                              CarriagePartKind partKind) {
        Optional<BuilderOpenRequest> built = BuilderOpenRequest.forSelection(subType, id, partKind);
        assertTrue(built.isPresent(), "expected a request for " + subType + "/" + id);
        return built.get();
    }
}
