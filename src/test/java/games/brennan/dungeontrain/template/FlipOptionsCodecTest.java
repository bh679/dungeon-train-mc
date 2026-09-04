package games.brennan.dungeontrain.template;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-template random-flip options and their {@code weights.json} round trip. */
final class FlipOptionsCodecTest {

    private static final IntUnaryOperator CLAMP = v -> Math.max(0, Math.min(100, v));

    @Test
    @DisplayName("the default is X on, Y/Z off, rooms off")
    void defaultIsXOnly() {
        assertTrue(FlipOptions.DEFAULT.x());
        assertFalse(FlipOptions.DEFAULT.y());
        assertFalse(FlipOptions.DEFAULT.z());
        assertFalse(FlipOptions.DEFAULT.rooms());
        assertTrue(FlipOptions.DEFAULT.isDefault());
    }

    @Test
    @DisplayName("an entry with no flip block reads as the default (X on)")
    void absentBlockIsDefault() {
        TemplateMeta m = TemplateWeightCodec.parseEntry(JsonParser.parseString("7"), CLAMP);
        assertNull(m.flip());
        assertEquals(FlipOptions.DEFAULT, m.effectiveFlip());
    }

    @Test
    @DisplayName("with() edits one named field and leaves the rest alone; an unknown name is a no-op")
    void withEditsOneField() {
        FlipOptions off = FlipOptions.DEFAULT.with("x", false);
        assertFalse(off.x());
        FlipOptions andZ = off.with("z", true);
        assertFalse(andZ.x());
        assertTrue(andZ.z());
        assertFalse(andZ.y());
        assertEquals(andZ, andZ.with("nonsense", true));
        assertTrue(andZ.noAxes() == false);
        assertTrue(FlipOptions.NONE.noAxes());
    }

    @Test
    @DisplayName("a flip block parses field by field, keeping the untouched fields at their defaults")
    void parsePartialBlock() {
        TemplateMeta m = TemplateWeightCodec.parseEntry(
            JsonParser.parseString("{\"weight\":4,\"flip\":{\"z\":true}}"), CLAMP);
        FlipOptions f = m.effectiveFlip();
        assertTrue(f.x(), "x stays on — the author only asked about z");
        assertTrue(f.z());
        assertFalse(f.y());
    }

    @Test
    @DisplayName("a non-default flip block round-trips, emitting only the changed fields")
    void roundTripsNonDefault() {
        FlipOptions flip = FlipOptions.DEFAULT.with("x", false).with("rooms", true);
        JsonObject json = TemplateWeightCodec.toJson(Map.of("maze",
            new TemplateMeta(4, TemplateGate.DEFAULT, null, null, flip)));
        JsonObject entry = json.getAsJsonObject("maze");
        JsonObject block = entry.getAsJsonObject("flip");
        assertFalse(block.get("x").getAsBoolean());
        assertTrue(block.get("rooms").getAsBoolean());
        assertFalse(block.has("y"), "y is at its default and is not emitted");
        assertEquals(flip, TemplateWeightCodec.parseEntry(entry, CLAMP).effectiveFlip());
    }

    @Test
    @DisplayName("a default flip block keeps the legacy bare-int form")
    void defaultFlipStaysBareInt() {
        JsonObject json = TemplateWeightCodec.toJson(Map.of("books",
            new TemplateMeta(11, TemplateGate.DEFAULT, null, null, FlipOptions.DEFAULT)));
        assertTrue(json.get("books").isJsonPrimitive(), "nothing non-default — stays a bare int");
        assertEquals(11, json.get("books").getAsInt());
    }

    @Test
    @DisplayName("the flip block survives a weight, gate or stage edit")
    void survivesOtherEdits() {
        FlipOptions flip = FlipOptions.DEFAULT.with("y", true);
        TemplateMeta m = new TemplateMeta(3, TemplateGate.DEFAULT, null, null, flip);
        assertEquals(flip, m.withWeight(9).effectiveFlip());
        assertEquals(flip, m.withStage("early").effectiveFlip());
        assertEquals(flip, TemplateMeta.mergeWeight(m, 9).effectiveFlip());
        assertEquals(flip, TemplateMeta.mergeGate(m, TemplateGate.DEFAULT, 1).effectiveFlip());
        assertEquals(9, TemplateMeta.mergeFlip(m.withWeight(9), flip, 1).weight());
    }
}
