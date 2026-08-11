package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.train.CarriagePartKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            assertEquals(subType, request(subType, "thing", partKind).subType(),
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

    private static BuilderOpenRequest request(BuilderNewOptions.SubType subType, String id,
                                              CarriagePartKind partKind) {
        Optional<BuilderOpenRequest> built = BuilderOpenRequest.forSelection(subType, id, partKind);
        assertTrue(built.isPresent(), "expected a request for " + subType + "/" + id);
        return built.get();
    }
}
