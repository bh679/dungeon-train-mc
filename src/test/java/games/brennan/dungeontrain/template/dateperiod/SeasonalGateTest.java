package games.brennan.dungeontrain.template.dateperiod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.template.GateContext;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.template.TemplateWeightCodec;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The date axis of the spawn gate: codec round trip, the hard gate, and the fallback filter. */
final class SeasonalGateTest {

    private static final TemplateGate XMAS = TemplateGate.DEFAULT.withPeriod("christmas", true);

    @AfterEach
    void reset() {
        DateToday.setForTesting(null);
    }

    @Test
    @DisplayName("A gate with no periods is the default and writes nothing")
    void defaultWritesNothing() {
        assertTrue(TemplateGate.DEFAULT.isDefault());
        JsonObject o = new JsonObject();
        TemplateWeightCodec.writeGateFields(o, TemplateGate.DEFAULT);
        assertEquals(0, o.size());
        assertFalse(XMAS.isDefault());
    }

    @Test
    @DisplayName("periods round-trip, sorted and lower-cased")
    void roundTrip() {
        JsonObject in = JsonParser.parseString("{\"weight\":1,\"periods\":[\"Halloween\",\"christmas\"]}").getAsJsonObject();
        TemplateGate g = TemplateWeightCodec.parseGate(in);
        assertEquals(List.of("christmas", "halloween"), List.copyOf(g.periods()));
        JsonObject out = new JsonObject();
        TemplateWeightCodec.writeGateFields(out, g);
        assertEquals("[\"christmas\",\"halloween\"]", out.get("periods").toString());
    }

    @Test
    @DisplayName("Level and phase edits keep the periods")
    void editsKeepPeriods() {
        TemplateGate g = XMAS.withMinLevel(5).withPhase(TrainPhase.NETHER, false).incMaxLevel();
        assertEquals(Set.of("christmas"), g.periods());
        assertTrue(XMAS.withPeriod("christmas", false).isDefault());
    }

    @Test
    @DisplayName("A seasonal gate allows only while one of its periods is on, even level-only")
    void hardGate() {
        GateContext ctx = new GateContext(0, TrainPhase.OVERWORLD);
        DateToday.setForTesting(LocalDate.parse("2026-07-01"));
        assertFalse(ctx.allows(XMAS));
        assertFalse(ctx.levelAllows(XMAS));
        assertTrue(ctx.allows(TemplateGate.DEFAULT));
        DateToday.setForTesting(LocalDate.parse("2026-12-20"));
        assertTrue(ctx.allows(XMAS));
    }

    @Test
    @DisplayName("Before any date is resolved, seasonal templates stay out")
    void unresolvedIsOff() {
        DateToday.setForTesting(null);
        assertFalse(GateContext.inSeason(XMAS));
        assertTrue(GateContext.inSeason(TemplateGate.DEFAULT));
    }

    @Test
    @DisplayName("The last-resort fallback drops out-of-season entries, unless that would empty it")
    void fallbackFilter() {
        DateToday.setForTesting(LocalDate.parse("2026-07-01"));
        List<TemplateGate> mixed = List.of(XMAS, TemplateGate.DEFAULT);
        assertEquals(List.of(TemplateGate.DEFAULT), GateContext.withoutOutOfSeason(mixed, g -> g));
        List<TemplateGate> allSeasonal = List.of(XMAS);
        assertSame(allSeasonal, GateContext.withoutOutOfSeason(allSeasonal, g -> g));
    }
}
