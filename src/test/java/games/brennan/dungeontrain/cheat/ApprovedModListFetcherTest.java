package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The request URL: this jar's DT version rides along so the relay can apply version floors. */
class ApprovedModListFetcherTest {

    private static final String BASE = "https://dt.example/cap";

    @Test
    @DisplayName("The DT version is sent as ?dt=")
    void sendsVersion() {
        assertEquals(BASE + "/approved-mods?dt=0.984.0",
            ApprovedModListFetcher.requestUrl(BASE, "0.984.0"));
        assertEquals(BASE + "/approved-mods?dt=1.0.0%2Bdev",
            ApprovedModListFetcher.requestUrl(BASE, " 1.0.0+dev "));
    }

    @Test
    @DisplayName("The verify POST carries the DT version and every file's fingerprint")
    void verifyBody() {
        assertEquals(BASE + "/approved-mods/verify", ApprovedModListFetcher.verifyUrl(BASE));
        List<ModFileFingerprint> files = List.of(
            new ModFileFingerprint("a.jar", 10, "a".repeat(40), 5L, List.of("alpha")),
            new ModFileFingerprint("b.jar", 20, "b".repeat(40), 4294967295L, List.of("beta", "gamma")));
        JsonObject o = JsonParser.parseString(ApprovedModListFetcher.verifyBody("0.1200.0", files)).getAsJsonObject();
        assertEquals("0.1200.0", o.get("dt").getAsString());
        assertEquals(2, o.getAsJsonArray("files").size());
        JsonObject b = o.getAsJsonArray("files").get(1).getAsJsonObject();
        assertEquals("b".repeat(40), b.get("sha1").getAsString());
        assertEquals(4294967295L, b.get("murmur2").getAsLong());
        assertEquals("gamma", b.getAsJsonArray("modIds").get(1).getAsString());
        assertFalse(JsonParser.parseString(ApprovedModListFetcher.verifyBody("?", files)).getAsJsonObject().has("dt"),
            "an unreadable version is omitted, as the GET omits its parameter");
    }

    @Test
    @DisplayName("An unreadable version sends no parameter (the relay treats the jar as too old)")
    void omitsUnknownVersion() {
        for (String v : new String[]{null, "", "  ", "?", "unknown"}) {
            assertEquals(BASE + "/approved-mods", ApprovedModListFetcher.requestUrl(BASE, v), String.valueOf(v));
        }
    }
}
