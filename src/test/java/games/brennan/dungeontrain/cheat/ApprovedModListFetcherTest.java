package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    @DisplayName("An unreadable version sends no parameter (the relay treats the jar as too old)")
    void omitsUnknownVersion() {
        for (String v : new String[]{null, "", "  ", "?", "unknown"}) {
            assertEquals(BASE + "/approved-mods", ApprovedModListFetcher.requestUrl(BASE, v), String.valueOf(v));
        }
    }
}
