package games.brennan.dungeontrain.builder.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frame translation a build undergoes on its way into the Whole pool.
 *
 * <p>Pure string in, string out, so the whole of {@link WholeRoomSidecars#translate} tests without a
 * world — the same reason {@code CarriageSnapshotTemplate}'s reshaping is tested and its stamping is
 * not. {@link WholeRoomSidecars#apply} writes files and invalidates caches, and is exercised in-game.</p>
 */
class WholeRoomSidecarsTest {

    /** A contents build's variants sidecar, keyed in interior-local coordinates. */
    private static final String CONTENTS_VARIANTS = """
        {
          "schemaVersion": 10,
          "variants": {
            "0,0,0": [{"state": "minecraft:chest", "weight": 1}],
            "3,2,1": [{"state": "minecraft:barrel", "weight": 5}]
          }
        }""";

    /** A containers sidecar with both of the cell-keyed sections. */
    private static final String CONTAINERS = """
        {
          "schemaVersion": 3,
          "pools": { "1,0,2": {"fillMin": 1, "fillMax": 2, "entries": []} },
          "links": { "4,1,0": "starter" }
        }""";

    private static JsonObject parse(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    @DisplayName("a contents build's cells shift by one on every axis")
    void contentsShiftsByOne() {
        JsonObject out = parse(WholeRoomSidecars.translate(CONTENTS_VARIANTS, 1)).getAsJsonObject("variants");
        assertTrue(out.has("1,1,1"), "0,0,0 should have become 1,1,1");
        assertTrue(out.has("4,3,2"), "3,2,1 should have become 4,3,2");
        assertFalse(out.has("0,0,0"), "the interior-frame key should be gone");
        assertEquals(2, out.size(), "no cell should be gained or lost");
    }

    @Test
    @DisplayName("a carriage build is already in the whole frame and shifts by nothing")
    void carriageDoesNotShift() {
        JsonObject out = parse(WholeRoomSidecars.translate(CONTENTS_VARIANTS, 0)).getAsJsonObject("variants");
        assertTrue(out.has("0,0,0"));
        assertTrue(out.has("3,2,1"));
    }

    @Test
    @DisplayName("both cell-keyed sections of a containers sidecar move")
    void containersPoolsAndLinksBothShift() {
        JsonObject out = parse(WholeRoomSidecars.translate(CONTAINERS, 1));
        assertTrue(out.getAsJsonObject("pools").has("2,1,3"));
        assertTrue(out.getAsJsonObject("links").has("5,2,1"));
        assertEquals("starter", out.getAsJsonObject("links").get("5,2,1").getAsString(),
            "the link's value must ride along with its key");
    }

    @Test
    @DisplayName("everything that is not a cell key survives the translation")
    void nonCellContentSurvives() {
        JsonObject out = parse(WholeRoomSidecars.translate(CONTAINERS, 1));
        assertEquals(3, out.get("schemaVersion").getAsInt(), "the schema version is not a cell");
        JsonObject pool = out.getAsJsonObject("pools").getAsJsonObject("2,1,3");
        assertEquals(1, pool.get("fillMin").getAsInt(), "an entry body is carried across untouched");
        assertEquals(2, pool.get("fillMax").getAsInt());
    }

    @Test
    @DisplayName("a key that is not a cell is left exactly as it came")
    void nonCellKeysAreUntouched() {
        assertEquals("notacell", WholeRoomSidecars.shiftCell("notacell", 1));
        assertEquals("1,2", WholeRoomSidecars.shiftCell("1,2", 1));
        assertEquals("a,b,c", WholeRoomSidecars.shiftCell("a,b,c", 1));
    }

    @Test
    @DisplayName("negative and zero coordinates shift like any other")
    void negativeCoordinatesShift() {
        assertEquals("0,1,2", WholeRoomSidecars.shiftCell("-1,0,1", 1));
        assertEquals("1,2,3", WholeRoomSidecars.shiftCell(" 0 , 1 , 2 ", 1));
    }

    @Test
    @DisplayName("a document that will not parse writes nothing rather than half of itself")
    void malformedDocumentYieldsNothing() {
        assertEquals("", WholeRoomSidecars.translate("{ not json", 1));
        assertEquals("", WholeRoomSidecars.translate("[]", 1));
    }

    @Test
    @DisplayName("only a contents build carries the interior offset")
    void shiftIsKindDependent() {
        assertEquals(1, WholeRoomSidecars.shiftFor(BuilderPhotoPaths.Kind.CONTENTS));
        assertEquals(0, WholeRoomSidecars.shiftFor(BuilderPhotoPaths.Kind.CARRIAGE));
    }

    @Test
    @DisplayName("the roles a whole room has a store for are the two it keeps")
    void rolesKept() {
        // parts and contents-allow are deliberately dropped — a whole room is stamped verbatim, so
        // nothing would ever read them. See the class javadoc.
        assertEquals("variants", WholeRoomSidecars.ROLE_VARIANTS);
        assertEquals("containers", WholeRoomSidecars.ROLE_CONTAINERS);
    }
}
