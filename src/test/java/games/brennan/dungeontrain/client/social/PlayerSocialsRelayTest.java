package games.brennan.dungeontrain.client.social;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerSocialsRelayTest {

    @Test
    void parsesSocialsAndSignInsDroppingWhatItCannotShow() {
        PlayerSocialsRelay.Status s = PlayerSocialsRelay.parseStatus("""
            {"ok":true,"socials":[
              {"platform":"instagram","handle":"alex.builds","tag":true,"via":"threads","ts":1},
              {"platform":"threads","handle":"alex.builds","tag":false,"via":"threads","ts":1},
              {"platform":"myspace","handle":"alex","tag":true},
              {"platform":"instagram","handle":"§cred name","tag":true}
            ],"signIns":["threads","instagram","bluesky"],"page":"https://dungeon.training/link/socials/"}""");
        assertNotNull(s);
        assertEquals(List.of("instagram", "threads"), s.socials().stream().map(PlayerSocialsRelay.Social::platform).toList());
        assertTrue(s.socials().get(0).tag());
        assertFalse(s.socials().get(1).tag());
        assertEquals("threads", s.socials().get(0).via());
        assertEquals(List.of("threads", "instagram"), s.signIns());
        assertEquals("https://dungeon.training/link/socials/", s.page());
    }

    @Test
    void anEmptyOrBrokenBodyIsHandled() {
        PlayerSocialsRelay.Status empty = PlayerSocialsRelay.parseStatus("{\"ok\":true}");
        assertNotNull(empty);
        assertTrue(empty.socials().isEmpty());
        assertNull(empty.page());
        assertNull(PlayerSocialsRelay.parseStatus("not json"));
    }

    @Test
    void aStartOnlyOpensHttpsUrls() {
        assertEquals("https://dungeon.training/api/dp-relay/player-socials/auth/start?via=threads&code=ab",
            PlayerSocialsRelay.parseUrl("{\"ok\":true,\"url\":\"https://dungeon.training/api/dp-relay/player-socials/auth/start?via=threads&code=ab\"}"));
        assertNull(PlayerSocialsRelay.parseUrl("{\"url\":\"file:///etc/passwd\"}"));
        assertNull(PlayerSocialsRelay.parseUrl("{\"ok\":true,\"socials\":[]}"));
    }
}
