package games.brennan.dungeontrain.template;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.worldgen.LapBand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Normalisation + JSON round-trip for the {@link Stage} gate-preset record. */
final class StageTest {

    @Test
    @DisplayName("id is lower-cased and name defaults to the id when blank")
    void normalises() {
        Stage s = new Stage("Deep_Nether", "  ", null);
        assertEquals("deep_nether", s.id());
        assertEquals("deep_nether", s.name());
        assertTrue(s.gate().isDefault());
    }

    @Test
    @DisplayName("toJson emits the name + (non-default) gate fields in the shared codec shape")
    void toJsonShape() {
        Stage s = new Stage("nether", "Deep Nether",
            new TemplateGate(10, TemplateGate.ALL, EnumSet.of(LapBand.V_NETHER)));
        JsonObject o = s.toJson();
        assertEquals("Deep Nether", o.get("name").getAsString());
        assertEquals(10, o.get("minLevel").getAsInt());
        // maxLevel ALL ⇒ omitted (absence means "all"); only NETHER ⇒ phases present.
        assertTrue(!o.has("maxLevel"));
        assertTrue(o.has("phases"));
    }

    @Test
    @DisplayName("fromJson(key,value) round-trips id + name + gate; a non-object value is a default Stage")
    void roundTrip() {
        Stage original = new Stage("endgame", "Endgame",
            new TemplateGate(40, 80, EnumSet.of(LapBand.V_END, LapBand.L_VOID)));
        Stage back = Stage.fromJson("endgame", original.toJson());
        assertEquals("endgame", back.id());
        assertEquals("Endgame", back.name());
        assertEquals(40, back.gate().minLevel());
        assertEquals(80, back.gate().maxLevel());
        assertEquals(EnumSet.of(LapBand.V_END, LapBand.L_VOID), back.gate().phases());

        Stage bare = Stage.fromJson("loose", JsonParser.parseString("\"junk\""));
        assertEquals("loose", bare.id());
        assertTrue(bare.gate().isDefault());
    }

    @Test
    @DisplayName("a builder credit rides in the same builder object a template's does, and clears to nothing")
    void builderRoundTrip() {
        Stage credited = new Stage("desert", "Desert", TemplateGate.DEFAULT)
            .withBuilder(new BuilderCredit("380df991-f603-344c-8e5b-000000000000", "Brennan"));
        assertTrue(credited.toJson().has(TemplateWeightCodec.K_BUILDER));
        Stage back = Stage.fromJson("desert", credited.toJson());
        assertEquals("Brennan", back.builder().name());
        assertEquals("380df991f603344c8e5b000000000000", back.builder().uuid(), "uuids are stored dashless");
        assertEquals(null, back.withBuilder(null).builder());
        assertEquals(null, Stage.fromJson("desert", back.withBuilder(null).toJson()).builder());
        assertEquals(null, new Stage("plain", "plain", TemplateGate.DEFAULT).builder(), "three-arg shape credits nobody");
    }
}
