package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Tribute webhook only ever accepts a Discord or Fluxer webhook link: the server fetches it on a player's say-so. */
class TributeWebhookTest {

    private static final String GOOD = "https://discord.com/api/webhooks/123456789012345678/AbC-def_GHI123";

    @ParameterizedTest
    @ValueSource(strings = {
            GOOD,
            GOOD + "/",
            "  " + GOOD + "  ",
            "https://discordapp.com/api/webhooks/1/tok",
            "https://ptb.discord.com/api/webhooks/1/tok",
            "https://canary.discord.com/api/webhooks/1/tok",
            "https://DISCORD.com/api/webhooks/1/tok",
    })
    @DisplayName("Discord webhook links are accepted")
    void accepts(String url) {
        assertTrue(TributeWebhook.isValid(url), url);
    }

    private static final String FLUXER_TOKEN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaB-c_9999999999999999999999999999";

    @ParameterizedTest
    @ValueSource(strings = {
            "https://fluxer.com/api/webhooks/1234567890123456789/" + FLUXER_TOKEN,
            "https://fluxer.com/api/v1/webhooks/1234567890123456789/" + FLUXER_TOKEN,
            "https://web.fluxer.app/api/webhooks/1/tok",
            "https://web.fluxer.app/api/v1/webhooks/1/tok/",
            "https://api.fluxer.app/webhooks/1/tok",
            "https://api.fluxer.app/v1/webhooks/1/tok",
            "https://API.Fluxer.app/v1/webhooks/1/tok",
    })
    @DisplayName("Fluxer webhook links are accepted")
    void acceptsFluxer(String url) {
        assertTrue(TributeWebhook.isValid(url), url);
        assertEquals("Fluxer", TributeWebhook.platform(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.fluxer.com/api/webhooks/1/tok",
            "https://fluxer.app/api/webhooks/1/tok",
            "https://fluxer.example.org/api/v1/webhooks/1/tok",
            "https://fluxer.com.evil.io/api/webhooks/1/tok",
            "http://api.fluxer.app/webhooks/1/tok",
            "https://api.fluxer.app:8443/webhooks/1/tok",
            "https://api.fluxer.app/webhooks/1/tok?wait=true",
            "https://api.fluxer.app/api/webhooks/1/tok",
            "https://fluxer.com/webhooks/1/tok",
            "https://fluxer.com/api/v2/webhooks/1/tok",
            "https://fluxer.com/api/webhooks/1/tok/github",
            "https://discord.com/api/v1/webhooks/1/tok",
            "https://discord.com/webhooks/1/tok",
    })
    @DisplayName("Fluxer look-alikes, self-hosted instances and wrong paths are refused")
    void refusesFluxerLookAlikes(String url) {
        assertFalse(TributeWebhook.isValid(url), url);
    }

    @Test
    @DisplayName("Fluxer links mask, give their id, and match their other spellings")
    void fluxerLinkParts() {
        String url = "https://api.fluxer.app/v1/webhooks/1234567890123456789/" + FLUXER_TOKEN;
        assertEquals("https://api.fluxer.app/v1/webhooks/1234567890123456789/••••", TributeWebhook.masked(url));
        assertEquals("1234567890123456789", TributeWebhook.webhookId(url));
        assertEquals("1", TributeWebhook.webhookId("https://fluxer.com/api/webhooks/1/tok"));
        assertEquals("Discord", TributeWebhook.platform(GOOD));
        assertEquals("", TributeWebhook.platform("https://evil.io/x"));
        java.util.List<String> kept = TributeWebhook.cleanList(java.util.List.of(url, "https://API.fluxer.app/v1/webhooks/1234567890123456789/" + FLUXER_TOKEN + "/", GOOD));
        assertEquals(java.util.List.of(url, GOOD), kept);
        assertTrue(TributeWebhook.contains(kept, "https://Api.Fluxer.App/v1/webhooks/1234567890123456789/" + FLUXER_TOKEN));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "http://discord.com/api/webhooks/1/tok",
            "https://discord.com.evil.io/api/webhooks/1/tok",
            "https://evil.io/api/webhooks/1/tok",
            "https://discord.com@evil.io/api/webhooks/1/tok",
            "https://user@discord.com/api/webhooks/1/tok",
            "https://discord.com:8443/api/webhooks/1/tok",
            "https://127.0.0.1/api/webhooks/1/tok",
            "https://169.254.169.254/api/webhooks/1/tok",
            "https://discord.com/api/webhooks/1/tok?wait=true",
            "https://discord.com/api/webhooks/1/tok#x",
            "https://discord.com/api/webhooks/1/tok/slack",
            "https://discord.com/api/webhooks/abc/tok",
            "https://discord.com/api/channels/1/messages",
            "https://discord.com/api/webhooks/1/../../users/@me",
            "https://discord.com/api/webhooks/1/to%2Fk",
            "file:///etc/passwd",
            "discord.com/api/webhooks/1/tok",
    })
    @DisplayName("anything else is refused")
    void refuses(String url) {
        assertFalse(TributeWebhook.isValid(url), String.valueOf(url));
    }

    @Test
    @DisplayName("over-long links are refused")
    void tooLong() {
        assertFalse(TributeWebhook.isValid("https://discord.com/api/webhooks/1/" + "a".repeat(250)));
    }

    @Test
    @DisplayName("the masked form hides the token")
    void masked() {
        assertEquals("https://discord.com/api/webhooks/123456789012345678/••••", TributeWebhook.masked(GOOD));
        assertEquals("https://discord.com/api/webhooks/123456789012345678/••••", TributeWebhook.masked(GOOD + "/"));
        assertEquals("", TributeWebhook.masked("https://evil.io/x"));
    }

    @Test
    @DisplayName("posts can never ping anyone")
    void noPings() {
        JsonObject payload = TributeWebhook.payload("@everyone", null, TributeWebhook.embed("t", "d", 1, true));
        assertEquals(0, payload.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        assertEquals("attachment://photo.png",
                payload.getAsJsonArray("embeds").get(0).getAsJsonObject().getAsJsonObject("image").get("url").getAsString());
    }

    @Test
    @DisplayName("names Discord refuses are left off, so the post still goes")
    void safeUsername() {
        assertEquals("Steve", TributeWebhook.safeUsername("Steve"));
        assertEquals(null, TributeWebhook.safeUsername("DiscordFan"));
        assertEquals(null, TributeWebhook.safeUsername("xX_Clyde_Xx"));
    }

    @Test
    @DisplayName("the kept list drops invalid links and repeats, and stops at the cap")
    void cleanList() {
        java.util.List<String> raw = new java.util.ArrayList<>();
        raw.add("https://evil.io/api/webhooks/1/x");
        raw.add(GOOD);
        raw.add(GOOD + "/");
        raw.add("https://DISCORD.com/api/webhooks/123456789012345678/AbC-def_GHI123");
        for (int i = 0; i < 40; i++) raw.add("https://discord.com/api/webhooks/" + (1000 + i) + "/t");
        java.util.List<String> kept = TributeWebhook.cleanList(raw);
        assertEquals(TributeWebhook.MAX_LINKS, kept.size());
        assertEquals(GOOD, kept.get(0));
        assertEquals("https://discord.com/api/webhooks/1000/t", kept.get(1));
        assertTrue(TributeWebhook.contains(kept, GOOD + "/"));
        assertFalse(TributeWebhook.contains(kept, "https://discord.com/api/webhooks/2000/t"));
    }

    @Test
    @DisplayName("an invalid link never posts")
    void invalidNeverPosts() {
        assertFalse(TributeWebhook.post("https://evil.io/x", "Steve", null, TributeWebhook.embed("t", "", 1, false), null).join());
    }
}
