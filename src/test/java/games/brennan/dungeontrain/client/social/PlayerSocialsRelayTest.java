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

    @Test
    void profileLinksPointAtThePlatform() {
        assertEquals("https://www.instagram.com/alex.builds/", PlayerSocialsRelay.profileUrl("instagram", "alex.builds"));
        assertEquals("https://www.threads.com/@alex.builds", PlayerSocialsRelay.profileUrl("threads", "alex.builds"));
        assertNull(PlayerSocialsRelay.profileUrl("myspace", "alex"));
        assertNull(PlayerSocialsRelay.profileUrl("instagram", "../evil"));
    }

    @Test
    void instagramSignsInByDmCodeFirstAndNeverNeedsThreads() {
        assertEquals("instagram_dm", PlayerSocialsRelay.signInFor("instagram", List.of("threads", "instagram_dm")));
        assertEquals("instagram", PlayerSocialsRelay.signInFor("instagram", List.of("threads", "instagram")));
        assertEquals("threads", PlayerSocialsRelay.signInFor("instagram", List.of("threads")));
        assertEquals("threads", PlayerSocialsRelay.signInFor("threads", List.of("instagram_dm", "threads")));
        assertNull(PlayerSocialsRelay.signInFor("threads", List.of("instagram_dm")));
        assertNull(PlayerSocialsRelay.signInFor("instagram", List.of()));
    }

    @Test
    void aDmCodeIsReadFromItsAnswerAndFromTheList() {
        PlayerSocialsRelay.DmCode c = PlayerSocialsRelay.parseDmCode(
            "{\"ok\":true,\"code\":\"DT-7K2QH\",\"expiresAt\":1900000000000,\"dmUrl\":\"https://ig.me/m/dungeontrain\"}");
        assertNotNull(c);
        assertEquals("DT-7K2QH", c.code());
        assertEquals(1900000000000L, c.expiresAt());
        assertEquals("https://ig.me/m/dungeontrain", c.dmUrl());
        assertNull(PlayerSocialsRelay.parseDmCode("{\"code\":\"DT-7K2QH\",\"expiresAt\":1,\"dmUrl\":\"https://evil.example/\"}"));
        assertNull(PlayerSocialsRelay.parseDmCode("{\"code\":\"<b>hi</b>\",\"expiresAt\":1,\"dmUrl\":\"https://ig.me/m/dungeontrain\"}"));
        assertNull(PlayerSocialsRelay.parseDmCode("{\"ok\":true,\"socials\":[]}"));

        PlayerSocialsRelay.Status s = PlayerSocialsRelay.parseStatus(
            "{\"socials\":[],\"signIns\":[\"threads\",\"instagram_dm\"],\"pendingCode\":{\"code\":\"DT-7K2QH\",\"expiresAt\":1900000000000,\"dmUrl\":\"https://ig.me/m/dungeontrain\"}}");
        assertNotNull(s);
        assertEquals(List.of("threads", "instagram_dm"), s.signIns());
        assertNotNull(s.pendingCode());
        assertEquals("DT-7K2QH", s.pendingCode().code());
        assertNull(PlayerSocialsRelay.parseStatus("{\"socials\":[],\"pendingCode\":null}").pendingCode());
    }
}
