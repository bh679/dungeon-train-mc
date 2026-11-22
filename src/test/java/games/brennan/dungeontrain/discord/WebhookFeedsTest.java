package games.brennan.dungeontrain.discord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each saved webhook link has a name and its own feeds; the server gets only the feeds. */
class WebhookFeedsTest {

    private static final String A = "https://discord.com/api/webhooks/111/tokA";
    private static final String B = "https://discord.com/api/webhooks/222/tokB";
    private static final String C = "https://discord.com/api/webhooks/333/tokC";

    @Test
    @DisplayName("feed masks round-trip through the config text")
    void feedText() {
        assertEquals("tributes,passenger_log", WebhookFeed.encode(WebhookFeed.ALL));
        assertEquals(WebhookFeed.ALL, WebhookFeed.decode("tributes, passenger_log"));
        assertEquals(WebhookFeed.PASSENGER_LOG.bit, WebhookFeed.decode("passenger_log,bogus"));
        assertEquals("", WebhookFeed.encode(0));
        assertEquals(0, WebhookFeed.decode(""));
        assertEquals(WebhookFeed.ALL, WebhookFeed.clamp(0xFF));
    }

    @Test
    @DisplayName("a link saved before feeds existed gets Tributes only")
    void legacyLinkGetsTributes() {
        Map<String, String> feeds = WebhookSettings.parse(List.of("222=passenger_log", "333="));
        assertEquals(WebhookFeed.TRIBUTES.bit, WebhookSettings.feedsFor(feeds, "111"));
        assertEquals(WebhookFeed.PASSENGER_LOG.bit, WebhookSettings.feedsFor(feeds, "222"));
        assertEquals(0, WebhookSettings.feedsFor(feeds, "333"));
    }

    @Test
    @DisplayName("settings lines keep only real ids and only saved links")
    void settingsLines() {
        Map<String, String> m = WebhookSettings.parse(List.of("111=Family = home", "abc=x", "=y", "nope", "222=Old", "222=New"));
        assertEquals(Map.of("111", "Family = home", "222", "New"), m);
        assertEquals(List.of("111=Family = home"), WebhookSettings.encode(m, List.of("111")));
        assertTrue(WebhookSettings.isId("123"));
        assertFalse(WebhookSettings.isId("12a"));
    }

    @Test
    @DisplayName("names are trimmed, cleaned and capped")
    void names() {
        assertEquals("Family", WebhookSettings.cleanName("  Family\n "));
        assertEquals("", WebhookSettings.cleanName(null));
        assertEquals(WebhookSettings.MAX_NAME_LENGTH, WebhookSettings.cleanName("x".repeat(100)).length());
    }

    @Test
    @DisplayName("listed by name, unnamed last in the order added")
    void displayOrder() {
        Map<String, String> names = Map.of(A, "zeta", C, "Alpha");
        assertEquals(List.of(C, A, B), WebhookSettings.displayOrder(List.of(A, B, C), u -> names.getOrDefault(u, "")));
    }

    @Test
    @DisplayName("the server keeps valid, distinct links and picks them per feed")
    void linksPerFeed() {
        List<WebhookLink> links = WebhookLink.cleanList(List.of(
                new WebhookLink(A, WebhookFeed.ALL),
                new WebhookLink(A + "/", WebhookFeed.TRIBUTES.bit),
                new WebhookLink("https://evil.io/api/webhooks/1/x", WebhookFeed.ALL),
                new WebhookLink(B, WebhookFeed.TRIBUTES.bit),
                new WebhookLink(C, WebhookFeed.PASSENGER_LOG.bit)));
        assertEquals(3, links.size());
        assertEquals(List.of(A, B), WebhookLink.urlsFor(links, WebhookFeed.TRIBUTES));
        assertEquals(List.of(A, C), WebhookLink.urlsFor(links, WebhookFeed.PASSENGER_LOG));
    }

    @Test
    @DisplayName("the webhook id is read from a link, never the token")
    void webhookId() {
        assertEquals("111", TributeWebhook.webhookId(A));
        assertEquals("111", TributeWebhook.webhookId(A + "/"));
        assertEquals("", TributeWebhook.webhookId("https://evil.io/api/webhooks/1/x"));
    }

    @Test
    @DisplayName("a long story is cut to Discord's limits instead of being refused whole")
    void embedLimits() {
        List<TributeWebhook.Field> fields = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) fields.add(new TributeWebhook.Field("Field " + i, "v".repeat(2000)));
        JsonObject e = TributeWebhook.embed("t".repeat(400), "d".repeat(9000), 1, fields, "ride.jpg");
        assertEquals(TributeWebhook.MAX_TITLE, e.get("title").getAsString().length());
        assertTrue(e.get("description").getAsString().length() <= TributeWebhook.MAX_DESCRIPTION);
        int total = e.get("title").getAsString().length() + e.get("description").getAsString().length();
        if (e.has("fields")) {
            assertTrue(e.getAsJsonArray("fields").size() <= TributeWebhook.MAX_FIELDS);
            for (var f : e.getAsJsonArray("fields")) {
                String v = f.getAsJsonObject().get("value").getAsString();
                assertTrue(v.length() <= TributeWebhook.MAX_FIELD_VALUE);
                total += v.length() + f.getAsJsonObject().get("name").getAsString().length();
            }
        }
        assertTrue(total <= 6000);
        assertEquals("attachment://ride.jpg", e.getAsJsonObject("image").get("url").getAsString());
    }

    @Test
    @DisplayName("a JPEG attachment is sent as a JPEG under its own name")
    void jpegAttachment() {
        String body = new String(TributeWebhook.multipart("B", "{}", new byte[]{1, 2}, "ride.jpg"), StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("filename=\"ride.jpg\""));
        assertTrue(body.contains("Content-Type: image/jpeg"));
        String png = new String(TributeWebhook.multipart("B", "{}", new byte[]{1}), StandardCharsets.ISO_8859_1);
        assertTrue(png.contains("filename=\"photo.png\"") && png.contains("Content-Type: image/png"));
    }
}
