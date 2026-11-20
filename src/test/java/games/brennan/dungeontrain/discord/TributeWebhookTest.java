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

/** The Tribute webhook only ever accepts a Discord webhook link: the server fetches it on a player's say-so. */
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
        JsonObject payload = TributeWebhook.payload("@everyone", TributeWebhook.embed("t", "d", 1, true));
        assertEquals(0, payload.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
        assertEquals("attachment://photo.png",
                payload.getAsJsonArray("embeds").get(0).getAsJsonObject().getAsJsonObject("image").get("url").getAsString());
    }

    @Test
    @DisplayName("an invalid link never posts")
    void invalidNeverPosts() {
        assertFalse(TributeWebhook.post("https://evil.io/x", "Steve", TributeWebhook.embed("t", "", 1, false), null).join());
    }
}
