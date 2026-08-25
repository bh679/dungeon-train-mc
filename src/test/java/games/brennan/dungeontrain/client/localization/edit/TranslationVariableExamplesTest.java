package games.brennan.dungeontrain.client.localization.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing the shipped examples file. Everything malformed has to degrade to "no examples" rather
 * than throw — this file decorates a tooltip, and nothing about it is worth taking a screen down.
 */
class TranslationVariableExamplesTest {

    private static Map<String, Map<Integer, TranslationVariableExamples.Entry>> parse(String json) {
        return TranslationVariableExamples.parse(new StringReader(json));
    }

    @Test
    @DisplayName("a well-formed file parses into key -> slot -> entry")
    void parsesTheShippedShape() {
        var parsed = parse("""
            {
              "chat.dungeontrain.familiar_book.4": {
                "1": { "label": "a player name", "examples": ["Steve", "Alex"] },
                "2": { "label": "a duration", "examples": ["3 minutes"] }
              }
            }
            """);
        assertEquals(1, parsed.size());
        var slots = parsed.get("chat.dungeontrain.familiar_book.4");
        assertEquals("a player name", slots.get(1).label());
        assertEquals(List.of("Steve", "Alex"), slots.get(1).examples());
        assertEquals(List.of("3 minutes"), slots.get(2).examples());
    }

    @Test
    @DisplayName("a label with no examples is still an entry; an empty one is not")
    void labelOnlyEntriesSurvive() {
        var parsed = parse("""
            { "a.key": { "1": { "label": "a player name" }, "2": { "examples": [] } } }
            """);
        assertEquals(1, parsed.get("a.key").size());
        assertEquals("a player name", parsed.get("a.key").get(1).label());
        assertNull(parsed.get("a.key").get(2));
    }

    @Test
    @DisplayName("malformed input yields an empty map instead of throwing")
    void malformedInputIsEmpty() {
        assertTrue(parse("[]").isEmpty());
        assertTrue(parse("\"nope\"").isEmpty());
        assertTrue(parse("{ \"a.key\": \"not an object\" }").isEmpty());
        assertTrue(parse("{ \"a.key\": { \"first\": { \"label\": \"x\" } } }").isEmpty());
    }

    @Test
    @DisplayName("lookup misses cleanly for an unknown key or slot")
    void lookupMissesReturnNull() {
        assertNull(TranslationVariableExamples.lookup("no.such.key", 1));
    }
}
