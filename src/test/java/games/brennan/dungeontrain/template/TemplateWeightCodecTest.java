package games.brennan.dungeontrain.template;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trip + backward-compat tests for the weights.json codec (bare int OR gate object). */
final class TemplateWeightCodecTest {

    private static final IntUnaryOperator CLAMP = v -> Math.max(0, Math.min(100, v));

    @Test
    @DisplayName("bare int parses as weight-only with the default gate (legacy form)")
    void parseBareInt() {
        TemplateMeta m = TemplateWeightCodec.parseEntry(JsonParser.parseString("20"), CLAMP);
        assertEquals(20, m.weight());
        assertTrue(m.gate().isDefault());
    }

    @Test
    @DisplayName("object form parses weight + level band + phases; maxLevel accepts \"all\"")
    void parseObject() {
        TemplateMeta m = TemplateWeightCodec.parseEntry(
            JsonParser.parseString("{\"weight\":5,\"minLevel\":3,\"maxLevel\":\"all\",\"phases\":[\"NETHER\",\"VOID\"]}"),
            CLAMP);
        assertEquals(5, m.weight());
        assertEquals(3, m.gate().minLevel());
        assertEquals(TemplateGate.ALL, m.gate().maxLevel());
        assertEquals(EnumSet.of(TrainPhase.NETHER, TrainPhase.VOID), m.gate().phases());
    }

    @Test
    @DisplayName("invalid entries (non-numeric, object without weight) return null")
    void parseInvalid() {
        assertNull(TemplateWeightCodec.parseEntry(JsonParser.parseString("\"nope\""), CLAMP));
        assertNull(TemplateWeightCodec.parseEntry(JsonParser.parseString("{\"minLevel\":2}"), CLAMP));
    }

    @Test
    @DisplayName("default-gate entries serialise back to a bare int; non-default to an object")
    void emitForm() {
        JsonObject out = TemplateWeightCodec.toJson(Map.of(
            "plain", TemplateMeta.of(20),
            "gated", new TemplateMeta(5, new TemplateGate(3, 40, EnumSet.of(TrainPhase.NETHER)))));
        // plain → bare int
        assertTrue(out.get("plain").isJsonPrimitive());
        assertEquals(20, out.get("plain").getAsInt());
        // gated → object with the non-default fields present
        assertTrue(out.get("gated").isJsonObject());
        JsonObject g = out.getAsJsonObject("gated");
        assertEquals(5, g.get("weight").getAsInt());
        assertEquals(3, g.get("minLevel").getAsInt());
        assertEquals(40, g.get("maxLevel").getAsInt());
        assertTrue(g.has("phases"));
    }

    @Test
    @DisplayName("a full round-trip preserves weight + gate")
    void roundTrip() {
        TemplateMeta original = new TemplateMeta(7, new TemplateGate(2, TemplateGate.ALL, EnumSet.of(TrainPhase.END)));
        JsonObject json = TemplateWeightCodec.toJson(Map.of("x", original));
        TemplateMeta back = TemplateWeightCodec.parseEntry(json.get("x"), CLAMP);
        assertEquals(original.weight(), back.weight());
        assertEquals(original.gate().minLevel(), back.gate().minLevel());
        assertEquals(original.gate().maxLevel(), back.gate().maxLevel());
        assertEquals(original.gate().phases(), back.gate().phases());
        assertFalse(back.gate().isDefault());
    }

    // ---- Stage link (v2 of the codec — optional "stage" field) ----

    @Test
    @DisplayName("absent stage key parses as Custom (null stageId) — pre-feature files unchanged")
    void parseNoStageLink() {
        TemplateMeta bare = TemplateWeightCodec.parseEntry(JsonParser.parseString("20"), CLAMP);
        assertNull(bare.stageId());
        TemplateMeta obj = TemplateWeightCodec.parseEntry(
            JsonParser.parseString("{\"weight\":5,\"minLevel\":3}"), CLAMP);
        assertNull(obj.stageId());
    }

    @Test
    @DisplayName("stage key parses into stageId (lower-cased); blank stage is normalised to null")
    void parseStageLink() {
        TemplateMeta m = TemplateWeightCodec.parseEntry(
            JsonParser.parseString("{\"weight\":5,\"stage\":\"Deep_Nether\"}"), CLAMP);
        assertEquals("deep_nether", m.stageId());
        assertTrue(m.isLinked());
        TemplateMeta blank = TemplateWeightCodec.parseEntry(
            JsonParser.parseString("{\"weight\":5,\"stage\":\"\"}"), CLAMP);
        assertNull(blank.stageId());
    }

    @Test
    @DisplayName("a linked entry with a DEFAULT inline gate still serialises as an object carrying stage")
    void emitLinkedDefaultGate() {
        JsonObject out = TemplateWeightCodec.toJson(Map.of(
            "linked", new TemplateMeta(4, TemplateGate.DEFAULT, "nether")));
        assertTrue(out.get("linked").isJsonObject());
        JsonObject o = out.getAsJsonObject("linked");
        assertEquals(4, o.get("weight").getAsInt());
        assertEquals("nether", o.get("stage").getAsString());
        // Default inline gate ⇒ no gate fields emitted.
        assertFalse(o.has("minLevel"));
        assertFalse(o.has("maxLevel"));
        assertFalse(o.has("phases"));
    }

    @Test
    @DisplayName("round-trip preserves the stage link alongside the inline snapshot gate")
    void roundTripLinked() {
        TemplateMeta original = new TemplateMeta(
            9, new TemplateGate(5, 30, EnumSet.of(TrainPhase.NETHER)), "endgame");
        JsonObject json = TemplateWeightCodec.toJson(Map.of("x", original));
        TemplateMeta back = TemplateWeightCodec.parseEntry(json.get("x"), CLAMP);
        assertEquals("endgame", back.stageId());
        assertEquals(5, back.gate().minLevel());
        assertEquals(30, back.gate().maxLevel());
        assertEquals(EnumSet.of(TrainPhase.NETHER), back.gate().phases());
    }
}
