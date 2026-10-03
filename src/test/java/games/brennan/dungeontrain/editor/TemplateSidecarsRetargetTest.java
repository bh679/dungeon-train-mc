package games.brennan.dungeontrain.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.track.variant.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a sidecar document keeps when a Workbench build is committed as a kind other than the one
 * the relay called it ({@link TemplateSidecars#retargeted}).
 */
final class TemplateSidecarsRetargetTest {

    private static final String PORTAL_ROOM_DOC = """
        {"files":{"variants":"V","contents-allow":"A","copies":"C","containers":"K"},
         "weights":{"weight":3,"mode":"door-left"},
         "credit":{"uuid":"0000-1111","name":"Alex","ts":1}}
        """;

    @Test
    @DisplayName("the same kind and sub kind pass the document through untouched")
    void sameKindPassesThrough() {
        assertSame(PORTAL_ROOM_DOC, TemplateSidecars.retargeted(PORTAL_ROOM_DOC,
            BuilderPhotoPaths.Kind.PORTAL_ROOM, "", BuilderPhotoPaths.Kind.PORTAL_ROOM, ""));
    }

    @Test
    @DisplayName("a room committed as contents keeps its variants, chest links and byline, loses the room-only roles and the weights")
    void portalRoomToContents() {
        JsonObject out = JsonParser.parseString(TemplateSidecars.retargeted(PORTAL_ROOM_DOC,
            BuilderPhotoPaths.Kind.PORTAL_ROOM, "", BuilderPhotoPaths.Kind.CONTENTS, "")).getAsJsonObject();
        JsonObject files = out.getAsJsonObject("files");
        assertEquals("V", files.get("variants").getAsString());
        assertEquals("K", files.get("containers").getAsString());
        assertFalse(files.has("contents-allow"), "contents have no allow-list");
        assertFalse(files.has("copies"), "contents have no copies variant");
        assertFalse(out.has("weights"), "a room's door tag must never land on a contents weight entry");
        assertTrue(out.has("credit"));
    }

    @Test
    @DisplayName("a room committed as a building keeps only the byline and chest links")
    void portalRoomToBuilding() {
        JsonObject out = JsonParser.parseString(TemplateSidecars.retargeted(PORTAL_ROOM_DOC,
            BuilderPhotoPaths.Kind.PORTAL_ROOM, "", BuilderPhotoPaths.Kind.BUILDING, "")).getAsJsonObject();
        JsonObject files = out.getAsJsonObject("files");
        assertEquals(1, files.size(), files.toString());
        assertTrue(files.has("containers"));
        assertTrue(out.has("credit"));
    }

    @Test
    @DisplayName("a track sub kind change keeps variants — every track kind has that role")
    void trackKindChange() {
        JsonObject out = JsonParser.parseString(TemplateSidecars.retargeted(PORTAL_ROOM_DOC,
            BuilderPhotoPaths.Kind.TRACK, TrackKind.TILE.id(), BuilderPhotoPaths.Kind.TRACK,
            TrackKind.TUNNEL_SECTION.id())).getAsJsonObject();
        assertTrue(out.getAsJsonObject("files").has("variants"));
    }

    @Test
    @DisplayName("an unparseable or blank document comes back blank rather than throwing")
    void garbageIsBlank() {
        assertEquals("", TemplateSidecars.retargeted("not json", BuilderPhotoPaths.Kind.CARRIAGE, "",
            BuilderPhotoPaths.Kind.CONTENTS, ""));
        assertEquals("", TemplateSidecars.retargeted("", BuilderPhotoPaths.Kind.CARRIAGE, "",
            BuilderPhotoPaths.Kind.CONTENTS, ""));
    }
}
