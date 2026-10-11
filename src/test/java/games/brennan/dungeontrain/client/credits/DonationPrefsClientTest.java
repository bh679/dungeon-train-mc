package games.brennan.dungeontrain.client.credits;

import com.google.gson.JsonObject;
import games.brennan.dungeontrain.client.credits.DonationPrefsClient.Prefs;
import games.brennan.dungeontrain.client.credits.DonationPrefsClient.Visibility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

final class DonationPrefsClientTest {

    private static final String UUID = "0f0e0d0c0b0a09080706050403020100";

    @Test
    @DisplayName("payload carries only the field being changed")
    void payloadCarriesOnlyTheChangedField() {
        JsonObject vis = DonationPrefsClient.buildPayload(UUID, Visibility.ANONYMOUS, null);
        assertEquals("{\"uuid\":\"" + UUID + "\",\"visibility\":\"anonymous\"}", vis.toString());
        JsonObject tag = DonationPrefsClient.buildPayload(UUID, null, false);
        assertEquals("{\"uuid\":\"" + UUID + "\",\"tag\":false}", tag.toString());
    }

    @Test
    @DisplayName("no uuid (no consent) means no request at all")
    void noUuidNoPayload() {
        assertNull(DonationPrefsClient.buildPayload(null, Visibility.OFF, null));
        assertNull(DonationPrefsClient.buildPayload("", null, true));
    }

    @Test
    @DisplayName("a 200 with every visibility parses")
    void parsesEveryVisibility() {
        for (Visibility v : Visibility.values()) {
            Prefs p = DonationPrefsClient.parse(200, "{\"ok\":true,\"visibility\":\"" + v.wireId() + "\",\"tag\":false}");
            assertEquals(new Prefs(true, v, false), p);
        }
    }

    @Test
    @DisplayName("an older relay, an error or an unreadable body fails rather than guessing")
    void failuresNeverGuess() {
        assertFalse(DonationPrefsClient.parse(404, "{\"error\":\"not_found\"}").ok());
        assertFalse(DonationPrefsClient.parse(400, "{\"error\":\"bad_uuid\"}").ok());
        assertFalse(DonationPrefsClient.parse(200, "{\"visibility\":\"loud\",\"tag\":true}").ok());
        assertFalse(DonationPrefsClient.parse(200, "{\"visibility\":\"public\",\"tag\":\"yes\"}").ok());
        assertFalse(DonationPrefsClient.parse(200, "{\"visibility\":\"public\"}").ok());
        assertFalse(DonationPrefsClient.parse(200, "not json").ok());
        assertFalse(DonationPrefsClient.parse(200, null).ok());
    }

    @Test
    @DisplayName("wire ids are case-insensitive and unknown ones are null")
    void wireIds() {
        assertEquals(Visibility.PRIVATE, Visibility.byWireId(" Private "));
        assertNull(Visibility.byWireId("everyone"));
        assertNull(Visibility.byWireId(null));
    }
}
