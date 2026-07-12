package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembly tests for {@link SharedBookReporter#buildPayload}. The shared-book submission must carry
 * the authored text plus the author's client language ({@code lang}) so the relay can store it for
 * language-matched delivery. Pure — no running server needed.
 */
class SharedBookReporterTest {

    @Test
    @DisplayName("buildPayload carries uuid, author, title, ordered pages, and lang")
    void payloadShape() {
        JsonObject out = SharedBookReporter.buildPayload(
                "069a79f444e94726a5befca90e38aaf5", "Notch", "My Journey",
                List.of("page one", "page two"), "pt_br");

        assertEquals("069a79f444e94726a5befca90e38aaf5", out.get("uuid").getAsString());
        assertEquals("Notch", out.get("author").getAsString());
        assertEquals("My Journey", out.get("title").getAsString());
        assertEquals(2, out.getAsJsonArray("pages").size());
        assertEquals("page one", out.getAsJsonArray("pages").get(0).getAsString());
        assertEquals("page two", out.getAsJsonArray("pages").get(1).getAsString());
        assertEquals("pt_br", out.get("lang").getAsString());
    }

    @Test
    @DisplayName("null author/title/lang become empty strings; null pages an empty array")
    void nullsBecomeEmpty() {
        JsonObject out = SharedBookReporter.buildPayload("uuid", null, null, null, null);

        assertEquals("", out.get("author").getAsString());
        assertEquals("", out.get("title").getAsString());
        assertTrue(out.has("pages"));
        assertEquals(0, out.getAsJsonArray("pages").size());
        assertEquals("", out.get("lang").getAsString());
    }
}
