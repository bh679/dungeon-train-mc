package games.brennan.dungeontrain.client.social;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebsiteNameRelayTest {

    @Test
    @DisplayName("Body carries uuid, name and the switch, matching the relay's /website-name contract")
    void body_carriesUuidNameAndSwitch() {
        JsonObject body = WebsiteNameRelay.buildBody("0123456789abcdef0123456789abcdef", "Steve_01", false);
        assertEquals("0123456789abcdef0123456789abcdef", body.get("uuid").getAsString());
        assertEquals("Steve_01", body.get("name").getAsString());
        assertFalse(body.get("on").getAsBoolean());
    }

    @Test
    @DisplayName("A name the relay would refuse is left out, so the switch still lands")
    void body_dropsNameTheRelayWouldRefuse() {
        assertFalse(WebsiteNameRelay.buildBody("u", "has space", true).has("name"));
        assertFalse(WebsiteNameRelay.buildBody("u", "seventeen_chars_x", true).has("name"));
        assertFalse(WebsiteNameRelay.buildBody("u", null, true).has("name"));
        assertTrue(WebsiteNameRelay.buildBody("u", null, true).get("on").getAsBoolean());
    }
}
