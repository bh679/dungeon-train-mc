package games.brennan.dungeontrain.editor.pool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PoolSidecarsTest {

    private static final String DOC = """
        {"version": 2, "variants": {"1,1,1": ["a"], "0,0,0": ["b"], "2,3,4": ["c"], "note": "x"},
         "pools": {"5,5,5": "p"}, "links": {"3,2,1": "loot"}, "other": {"9,9,9": "kept"}}
        """;

    @Test
    @DisplayName("box → contents: every cell moves one in, and cells on the shell are dropped")
    void intoContentsShiftsAndCrops() {
        JsonObject out = JsonParser.parseString(PoolSidecars.translate(DOC, -1, new Vec3i(11, 5, 5))).getAsJsonObject();
        JsonObject variants = out.getAsJsonObject("variants");
        assertTrue(variants.has("0,0,0"), "1,1,1 moved to the interior corner");
        assertFalse(variants.has("-1,-1,-1"), "the shell corner is outside the interior");
        assertTrue(variants.has("1,2,3"));
        assertTrue(variants.has("note"), "a key that is not a cell is carried untouched");
        assertEquals(3, variants.size());
        assertTrue(out.getAsJsonObject("pools").has("4,4,4"));
        assertTrue(out.getAsJsonObject("links").has("2,1,0"));
        assertTrue(out.getAsJsonObject("other").has("9,9,9"), "only the cell-keyed sections are translated");
        assertEquals(2, out.get("version").getAsInt());
    }

    @Test
    @DisplayName("contents → box: cells move one out, nothing is dropped")
    void outOfContentsShiftsOnly() {
        JsonObject out = JsonParser.parseString(PoolSidecars.translate(DOC, 1, null)).getAsJsonObject();
        JsonObject variants = out.getAsJsonObject("variants");
        assertTrue(variants.has("2,2,2"));
        assertTrue(variants.has("1,1,1"));
        assertTrue(variants.has("3,4,5"));
        assertEquals(4, variants.size());
    }

    @Test
    @DisplayName("same frame: the document round-trips; a document that will not parse yields nothing")
    void sameFrameAndGarbage() {
        JsonObject out = JsonParser.parseString(PoolSidecars.translate(DOC, 0, null)).getAsJsonObject();
        assertEquals(4, out.getAsJsonObject("variants").size());
        assertEquals("", PoolSidecars.translate("{not json", -1, new Vec3i(1, 1, 1)));
    }

    @Test
    @DisplayName("cropping also drops a cell past the far face of the interior")
    void cropsFarFace() {
        String doc = "{\"variants\": {\"11,1,1\": [\"edge\"], \"10,1,1\": [\"in\"]}}";
        JsonObject out = JsonParser.parseString(PoolSidecars.translate(doc, -1, new Vec3i(10, 5, 5))).getAsJsonObject();
        JsonObject variants = out.getAsJsonObject("variants");
        assertFalse(variants.has("10,0,0"), "x = 10 is the far shell wall of a 10-long interior");
        assertTrue(variants.has("9,0,0"));
    }
}
