package games.brennan.dungeontrain.client.credits;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The team's own Builders / Writers rows on the Credits page. */
final class HouseCreditsTest {

    @Test
    @DisplayName("parse keeps named rows with a count, drops the rest")
    void parsesRows() {
        HouseCredits.Rows rows = HouseCredits.parse(JsonParser.parseString("""
            {"builders":[{"name":"Brennan Hatton","builds":144},{"name":"","builds":3},{"name":"Zero","builds":0}],
             "writers":[{"name":"Brennan Hatton","books":291},{"name":"Wilson Taylor","books":97},{"books":5}]}
            """));
        assertEquals(1, rows.builders().size());
        assertEquals("Brennan Hatton", rows.builders().get(0).name());
        assertEquals(144, rows.builders().get(0).templates());
        assertEquals("", rows.builders().get(0).uuid());
        assertEquals(List.of("Brennan Hatton", "Wilson Taylor"),
            rows.writers().stream().map(RelayWriters.Writer::name).toList());
        assertEquals(0, rows.writers().get(0).rank());
    }

    @Test
    @DisplayName("a missing or malformed file is no rows, never an error")
    void malformedIsEmpty() {
        assertTrue(HouseCredits.parse(JsonParser.parseString("[]")).builders().isEmpty());
        assertTrue(HouseCredits.parse(null).writers().isEmpty());
    }

    @Test
    @DisplayName("team writers are laid into the relay's list by book count")
    void writersMergeByCount() {
        List<RelayWriters.Writer> out = HouseCredits.withWriters(
            List.of(new RelayWriters.Writer("Ada", 120, false, 1), new RelayWriters.Writer("Bo", 30, false, 2)),
            List.of(new RelayWriters.Writer("Brennan Hatton", 291), new RelayWriters.Writer("Wilson Taylor", 97)));
        assertEquals(List.of("Brennan Hatton", "Ada", "Wilson Taylor", "Bo"),
            out.stream().map(RelayWriters.Writer::name).toList());
    }

    @Test
    @DisplayName("the team's builder row sorts in by count and never claims a player's uuid")
    void buildersMergeByCount() {
        List<TemplateBuilderCredits.Builder> out = TemplateBuilderCredits.withHouse(
            List.of(new TemplateBuilderCredits.Builder("abc", "Firefly", 10)),
            List.of(new TemplateBuilderCredits.Builder("", "Brennan Hatton", 144)));
        assertEquals(List.of("Brennan Hatton", "Firefly"),
            out.stream().map(TemplateBuilderCredits.Builder::display).toList());
        assertTrue(out.get(0).uuid().isEmpty());
    }

    @Test
    @DisplayName("the bundled file ships rows for the team")
    void bundledFileHasRows() {
        HouseCredits.reset();
        assertFalse(HouseCredits.builders().isEmpty());
        assertTrue(HouseCredits.writers().stream().anyMatch(w -> w.name().equals("Wilson Taylor")));
    }
}
