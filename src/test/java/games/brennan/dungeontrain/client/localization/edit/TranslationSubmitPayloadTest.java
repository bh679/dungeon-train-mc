package games.brennan.dungeontrain.client.localization.edit;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire contract between the mod and the relay's {@code /translations/submit}, and the
 * retry/drop verdict the durable outbox keys on. Pure — no relay, no Minecraft bootstrap.
 *
 * <p>The relay's own tests assert the server half of the same contract
 * ({@code translations.test.js}); these assert the half that has to match it.</p>
 */
class TranslationSubmitPayloadTest {

    private static final String UUID = "0123456789abcdef0123456789abcdef";

    private static JsonObject payload() {
        return TranslationSubmitClient.buildPayload(UUID, "老本願", "de_de", List.of(
            new TranslationSubmitClient.Unit("lang", "dungeontrain", "gui.dungeontrain.x",
                "Depart", "Abfahren"),
            new TranslationSubmitClient.Unit("book", "dungeontrain", "random_books/x#title",
                "Death Note", "Todesnotiz")));
    }

    @Test
    @DisplayName("the payload carries exactly the fields the relay's normalise reads")
    void payloadShape() {
        JsonObject body = payload();
        assertEquals(UUID, body.get("uuid").getAsString());
        assertEquals("老本願", body.get("translator").getAsString());
        assertEquals("de_de", body.get("locale").getAsString());
        assertTrue(body.has("modVersion"), "the relay records which build a fix came from");
        assertEquals(2, body.getAsJsonArray("units").size());

        JsonObject first = body.getAsJsonArray("units").get(0).getAsJsonObject();
        assertEquals("lang", first.get("type").getAsString());
        assertEquals("dungeontrain", first.get("namespace").getAsString());
        assertEquals("gui.dungeontrain.x", first.get("id").getAsString());
        assertEquals("Depart", first.get("source").getAsString());
        assertEquals("Abfahren", first.get("value").getAsString());
    }

    @Test
    @DisplayName("book units are tagged so the relay files them separately from lang keys")
    void bookUnitsAreTagged() {
        JsonObject second = payload().getAsJsonArray("units").get(1).getAsJsonObject();
        assertEquals("book", second.get("type").getAsString());
        assertEquals("random_books/x#title", second.get("id").getAsString());
    }

    @Test
    @DisplayName("the payload never carries a flag the client could use to self-approve")
    void noSelfApproval() {
        // The relay assigns flag/moderation/approvedBy; sending them would be a bug worth
        // catching here rather than relying on the server to ignore them.
        JsonObject body = payload();
        assertFalse(body.has("flag"));
        assertFalse(body.has("moderation"));
        assertFalse(body.has("approvedBy"));
    }

    @Test
    @DisplayName("nulls become empty strings rather than JSON nulls")
    void nullsAreEmptyStrings() {
        JsonObject body = TranslationSubmitClient.buildPayload(null, null, null, null);
        assertEquals("", body.get("uuid").getAsString());
        assertEquals("", body.get("translator").getAsString());
        assertEquals("", body.get("locale").getAsString());
        assertEquals(0, body.getAsJsonArray("units").size());
    }

    // ---- outbox retry verdicts ------------------------------------------------

    private static TranslationSubmitClient.SendResult verdict(int status, String body) {
        return TranslationSubmitClient.interpret(new FakeResponse(status, body));
    }

    @Test
    @DisplayName("a 200 succeeds and reports how many units were stored")
    void successReportsStored() {
        var r = verdict(200, "{\"ok\":true,\"stored\":3}");
        assertTrue(r.ok());
        assertEquals(3, r.stored());
    }

    @Test
    @DisplayName("the relay's dedupe reply is a success, not a failure")
    void dedupeIsSuccess() {
        // A retry of already-stored work comes back 200/stored:0. Treating it as a failure would
        // leave the item in the queue forever.
        var r = verdict(200, "{\"ok\":true,\"stored\":0,\"deduped\":true}");
        assertTrue(r.ok());
        assertEquals(0, r.stored());
    }

    @Test
    @DisplayName("429 and 5xx are retryable; a rejected payload is poison and gets dropped")
    void retryClassification() {
        assertTrue(verdict(429, "{}").retryable());
        assertTrue(verdict(503, "{}").retryable());
        assertTrue(verdict(408, "{}").retryable());
        // A payload the relay will never accept must not block the queue behind it.
        assertFalse(verdict(400, "{\"error\":\"bad_locale\"}").retryable());
        assertFalse(verdict(422, "{}").retryable());
    }

    @Test
    @DisplayName("a relay that predates the endpoint is retryable, not poison")
    void endpointMissingIsRetryable() {
        // The mod and the relay deploy separately, so a client can reach a relay without this
        // endpoint. Dropping on 404 would bin a translator's work for the gap between deploys.
        assertTrue(verdict(404, "{\"error\":\"not_found\"}").retryable());
        assertTrue(verdict(405, "{}").retryable());
        assertTrue(verdict(501, "{}").retryable());
    }

    @Test
    @DisplayName("a 2xx with an unreadable body still counts as delivered")
    void unreadableBodyStillSucceeds() {
        var r = verdict(200, "not json");
        assertTrue(r.ok());
        assertEquals(0, r.stored());
    }

    // ---- contributor unlock ---------------------------------------------------

    @Test
    @DisplayName("an approved count above zero unlocks; zero does not")
    void unlockParse() {
        assertEquals(2, TranslationContributor.parseApproved("{\"ok\":true,\"approved\":2}"));
        assertEquals(0, TranslationContributor.parseApproved("{\"ok\":true,\"approved\":0}"));
    }

    @Test
    @DisplayName("anything unparseable leaves the unlock alone rather than granting it")
    void unlockFailsClosed() {
        // Every one of these is reachable in the wild: a relay that predates the endpoint, an
        // error body, a proxy's HTML. None of them may unlock the unfiltered browse.
        assertNull(TranslationContributor.parseApproved("{\"error\":\"not_found\"}"));
        assertNull(TranslationContributor.parseApproved("{\"ok\":false,\"approved\":9}"));
        assertNull(TranslationContributor.parseApproved("{\"ok\":true}"));
        assertNull(TranslationContributor.parseApproved("{\"ok\":true,\"approved\":\"lots\"}"));
        assertNull(TranslationContributor.parseApproved("[]"));
    }

    /** Minimal {@link java.net.http.HttpResponse} — only status and body are ever read. */
    private record FakeResponse(int status, String body)
        implements java.net.http.HttpResponse<String> {

        @Override public int statusCode() { return status; }
        @Override public String body() { return body; }
        @Override public java.net.http.HttpRequest request() { return null; }
        @Override public java.util.Optional<java.net.http.HttpResponse<String>> previousResponse() {
            return java.util.Optional.empty();
        }
        @Override public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
        }
        @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return java.util.Optional.empty();
        }
        @Override public java.net.URI uri() { return java.net.URI.create("http://localhost/"); }
        @Override public java.net.http.HttpClient.Version version() {
            return java.net.http.HttpClient.Version.HTTP_1_1;
        }
    }
}
