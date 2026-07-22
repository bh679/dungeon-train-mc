package games.brennan.dungeontrain.client.links;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Tests for the relay {@code /links} response parser (pure Gson, no network). */
class OfficialLinksFetcherTest {

    @Test
    void parsesWellFormedResponse() {
        Map<String, String> out = OfficialLinksFetcher.parse(
                "{\"ok\":true,\"links\":{\"discord\":\"https://discord.gg/x\",\"patreon\":\"https://p.com/y\"}}");
        assertEquals(Map.of("discord", "https://discord.gg/x", "patreon", "https://p.com/y"), out);
    }

    @Test
    void nonStringValuesAreSkipped() {
        Map<String, String> out = OfficialLinksFetcher.parse(
                "{\"ok\":true,\"links\":{\"discord\":42,\"patreon\":\"https://p.com/y\",\"x\":{\"nested\":1}}}");
        assertEquals(Map.of("patreon", "https://p.com/y"), out);
    }

    @Test
    void malformedResponsesReturnNull() {
        assertNull(OfficialLinksFetcher.parse("[]"));
        assertNull(OfficialLinksFetcher.parse("{\"ok\":false,\"links\":{}}"));
        assertNull(OfficialLinksFetcher.parse("{\"ok\":true}"));
        assertNull(OfficialLinksFetcher.parse("{\"ok\":true,\"links\":[]}"));
    }

    @Test
    void emptyLinksObjectIsValidAndEmpty() {
        Map<String, String> out = OfficialLinksFetcher.parse("{\"ok\":true,\"links\":{}}");
        assertEquals(Map.of(), out);
    }
}
