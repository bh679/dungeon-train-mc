package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembly tests for {@link LetterReporter#buildPayload}. The letter submission must carry the
 * per-life series identity ({@code seriesId} + numeric {@code letterIndex}) alongside the shared-book
 * fields, so the relay can group letters into a series. Pure — no running server needed.
 */
class LetterReporterTest {

    @Test
    @DisplayName("buildPayload carries uuid, seriesId, numeric letterIndex, author, title, ordered pages")
    void payloadShape() {
        JsonObject out = LetterReporter.buildPayload(
                "069a79f444e94726a5befca90e38aaf5", "abc123series", 2,
                "Notch", "My Journey", List.of("page one", "page two"));

        assertEquals("069a79f444e94726a5befca90e38aaf5", out.get("uuid").getAsString());
        assertEquals("abc123series", out.get("seriesId").getAsString());
        assertTrue(out.getAsJsonPrimitive("letterIndex").isNumber(), "letterIndex must be a JSON number");
        assertEquals(2, out.get("letterIndex").getAsInt());
        assertEquals("Notch", out.get("author").getAsString());
        assertEquals("My Journey", out.get("title").getAsString());
        assertEquals(2, out.getAsJsonArray("pages").size());
        assertEquals("page one", out.getAsJsonArray("pages").get(0).getAsString());
        assertEquals("page two", out.getAsJsonArray("pages").get(1).getAsString());
    }

    @Test
    @DisplayName("null author/title/seriesId become empty strings; null pages an empty array")
    void nullsBecomeEmpty() {
        JsonObject out = LetterReporter.buildPayload("uuid", null, 1, null, null, null);

        assertEquals("", out.get("seriesId").getAsString());
        assertEquals("", out.get("author").getAsString());
        assertEquals("", out.get("title").getAsString());
        assertTrue(out.has("pages"));
        assertEquals(0, out.getAsJsonArray("pages").size());
    }
}
