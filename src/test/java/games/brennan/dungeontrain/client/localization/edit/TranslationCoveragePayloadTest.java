package games.brennan.dungeontrain.client.localization.edit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire contract for {@code /translations/coverage} -- the one call that brings every language's
 * ring up to date at startup. Pure: no relay, no Minecraft bootstrap.
 */
class TranslationCoveragePayloadTest {

    @Test
    @DisplayName("the long form: an object per locale carrying a reviewed count")
    void longForm() {
        Map<String, Integer> out = TranslationCoverageClient.parse(
            "{\"locales\":{\"zh_cn\":{\"reviewed\":1047},\"zh_tw\":{\"reviewed\":14}}}");
        assertEquals(2, out.size());
        assertEquals(1047, out.get("zh_cn"));
        assertEquals(14, out.get("zh_tw"));
    }

    @Test
    @DisplayName("the short form too, so the relay can answer with a bare number")
    void shortForm() {
        Map<String, Integer> out =
            TranslationCoverageClient.parse("{\"locales\":{\"de_de\":3}}");
        assertEquals(3, out.get("de_de"));
    }

    @Test
    @DisplayName("locale codes are lowercased, matching every other locale key in the mod")
    void localesAreNormalised() {
        assertEquals(5, TranslationCoverageClient.parse("{\"locales\":{\"PT_BR\":5}}").get("pt_br"));
    }

    @Test
    @DisplayName("zero and negative counts are dropped rather than stored")
    void nothingToReportIsNotReported() {
        // A zero would be indistinguishable from a real answer of "none reviewed", and both mean
        // the same thing to adjust() -- but keeping them would grow the map by 130 dead entries.
        Map<String, Integer> out = TranslationCoverageClient.parse(
            "{\"locales\":{\"de_de\":0,\"fr_fr\":-4,\"zh_cn\":9}}");
        assertEquals(1, out.size());
        assertEquals(9, out.get("zh_cn"));
    }

    @Test
    @DisplayName("anything unreadable leaves the baked counts standing, rather than zeroing them")
    void malformedBodiesAreEmpty() {
        // The failure mode that matters: a bad body must not be read as "nothing is reviewed",
        // which would silently inflate every ring back to its build-time size.
        for (String body : new String[] {
            null, "", "not json", "[]", "{}", "{\"locales\":[]}", "{\"locales\":{\"de_de\":\"lots\"}}"
        }) {
            assertTrue(TranslationCoverageClient.parse(body).isEmpty(), String.valueOf(body));
        }
    }
}
