package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.builder.BuilderNewOptions.SubType;
import games.brennan.dungeontrain.train.CarriagePartKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which template a New selection's photo belongs to.
 *
 * <p>The trap this guards: a Whole Carriage build photographs the <em>shell</em>, while a room or a
 * part photographs the thing that was picked. Get it the wrong way round and the picture lands
 * beside a template it isn't a picture of — and since the write succeeds either way, nothing would
 * report it.</p>
 */
final class BuilderPhotoRequestTest {

    @Test
    @DisplayName("A whole carriage is a picture of the shell it was stamped from")
    void wholeCarriageUsesTheShell() {
        BuilderPhotoRequest r = BuilderPhotoRequest
                .forSelection(SubType.WHOLE_CARRIAGE, "standard", "nether", null).orElseThrow();
        assertEquals(BuilderPhotoPaths.Kind.CARRIAGE, r.kind());
        assertEquals("standard", r.id(), "the pick was a stage, which has no file to sit beside");
    }

    @Test
    @DisplayName("A room is a picture of the contents that were picked, not of the shell")
    void carriageRoomUsesThePick() {
        BuilderPhotoRequest r = BuilderPhotoRequest
                .forSelection(SubType.CARRIAGE_ROOM, "standard", "library", null).orElseThrow();
        assertEquals(BuilderPhotoPaths.Kind.CONTENTS, r.kind());
        assertEquals("library", r.id());
    }

    @Test
    @DisplayName("A part carries its kind — part names are only unique within one")
    void partsCarryTheirKind() {
        BuilderPhotoRequest r = BuilderPhotoRequest
                .forSelection(SubType.PARTS, "standard", "sandstone", CarriagePartKind.WALLS)
                .orElseThrow();
        assertEquals(BuilderPhotoPaths.Kind.PART, r.kind());
        assertEquals("sandstone", r.id());
        assertEquals(CarriagePartKind.WALLS, r.partKind());
        assertEquals("walls", r.partKindId());
    }

    @Test
    @DisplayName("Nothing named means no request rather than a photo beside a guessed name")
    void unresolvableSelectionsAskForNothing() {
        assertTrue(BuilderPhotoRequest.forSelection(SubType.PARTS, "standard", "sandstone", null).isEmpty(),
                "a part with no kind has no directory");
        assertTrue(BuilderPhotoRequest.forSelection(SubType.CARRIAGE_ROOM, "standard", "", null).isEmpty());
        assertTrue(BuilderPhotoRequest.forSelection(SubType.WHOLE_CARRIAGE, "", "nether", null).isEmpty());
        assertTrue(BuilderPhotoRequest.forSelection(null, "standard", "x", null).isEmpty());
    }

    @Test
    @DisplayName("Every sub type answers, so a new one can't silently stop taking photos")
    void everySubTypeResolves() {
        for (SubType subType : SubType.values()) {
            Optional<BuilderPhotoRequest> r = BuilderPhotoRequest
                    .forSelection(subType, "standard", "picked", CarriagePartKind.FLOOR);
            assertTrue(r.isPresent(), subType.toString());
        }
    }
}
