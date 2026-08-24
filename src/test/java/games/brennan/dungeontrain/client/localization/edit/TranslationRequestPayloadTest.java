package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire contract between the mod and the relay's {@code /translations/request} — a vote for a
 * machine first draft of a language nobody has translated. Pure — no relay, no Minecraft bootstrap,
 * mirroring {@link TranslationSubmitPayloadTest}.
 */
class TranslationRequestPayloadTest {

    private static final String UUID = "0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("the payload carries the uuid, the locale and the build, and nothing else")
    void payloadShape() {
        JsonObject body = TranslationRequestClient.buildPayload(UUID, "hu_hu", "0.636.0");
        assertEquals(UUID, body.get("uuid").getAsString());
        assertEquals("hu_hu", body.get("locale").getAsString());
        assertEquals("0.636.0", body.get("modVersion").getAsString());
        assertEquals(3, body.size(),
            "a request is a vote: no text means nothing for a moderator to read");
        assertFalse(body.has("units"), "there is no unit list — the whole locale is the claim");
    }

    @Test
    @DisplayName("the locale is lowercased, as the relay's cleanLocale expects")
    void localeIsNormalised() {
        assertEquals("pt_br",
            TranslationRequestClient.buildPayload(UUID, "PT_BR", "1.0.0").get("locale").getAsString());
    }

    @Test
    @DisplayName("nulls become empty strings rather than JSON nulls")
    void nullsAreEmptyStrings() {
        JsonObject body = TranslationRequestClient.buildPayload(null, null, null);
        assertTrue(body.get("uuid").getAsString().isEmpty());
        assertTrue(body.get("locale").getAsString().isEmpty());
        assertTrue(body.get("modVersion").getAsString().isEmpty());
    }

    @Test
    @DisplayName("the count comes back off a well-formed body and nowhere else")
    void countParsing() {
        assertEquals(12, TranslationRequestClient.readCount("{\"locale\":\"hu_hu\",\"count\":12}"));
        assertEquals(0, TranslationRequestClient.readCount("{\"count\":0}"));
        // Every failure reads as "no number", which the button renders as no number at all —
        // never as a zero, which would read as a verdict on the language.
        assertEquals(-1, TranslationRequestClient.readCount("{\"count\":\"lots\"}"));
        assertEquals(-1, TranslationRequestClient.readCount("{}"));
        assertEquals(-1, TranslationRequestClient.readCount("[]"));
        assertEquals(-1, TranslationRequestClient.readCount("not json"));
        assertEquals(-1, TranslationRequestClient.readCount(null));
    }
}
