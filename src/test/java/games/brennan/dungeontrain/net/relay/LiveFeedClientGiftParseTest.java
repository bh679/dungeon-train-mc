package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonParser;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.GiftNotices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveFeedClientGiftParseTest {

    private static GiftNotices parse(String json) {
        return LiveFeedClient.parseGiftNotices(new LiveFeedClient.Result(200, JsonParser.parseString(json).getAsJsonObject(), null));
    }

    @Test
    @DisplayName("joins and gifts are read; malformed or odd-id entries are dropped")
    void parsed() {
        GiftNotices n = parse("""
            {"live":true,"seq":9,"messages":[],
             "joins":[
               {"seq":4,"joinId":"j-AbCdEfGh1234","uuid":"aaaaaaaabbbbccccddddeeeeeeeeeeee","name":"Viewer"},
               {"seq":5,"joinId":"/op @a","uuid":"x","name":"Evil"},
               {"seq":6,"joinId":"j-AbCdEfGh5678","uuid":"y","name":"§cRed"},
               {"joinId":"j-noseq123456"}
             ],
             "gifts":[
               {"seq":7,"giftId":"g-AbCdEfGh1234","target":"aaaaaaaabbbbccccddddeeeeeeeeeeee","from":"Streamer"},
               {"seq":8,"giftId":"g-AbCdEfGh5678","from":"NoTarget"}
             ]}""");
        assertEquals(2, n.joins().size());
        assertEquals("j-AbCdEfGh1234", n.joins().get(0).joinId());
        assertEquals("Viewer", n.joins().get(0).name());
        assertFalse(n.joins().get(1).name().contains("§"), "names are cleaned of formatting codes");
        assertEquals(1, n.gifts().size());
        assertEquals("Streamer", n.gifts().get(0).from());
        assertEquals(7, n.gifts().get(0).seq());
    }

    @Test
    @DisplayName("an older relay's page, or a failed poll, carries no notices")
    void absent() {
        assertTrue(parse("{\"live\":true,\"seq\":3,\"messages\":[]}").joins().isEmpty());
        assertTrue(LiveFeedClient.parseGiftNotices(new LiveFeedClient.Result(0, null, null)).gifts().isEmpty());
    }

    @Test
    @DisplayName("a close-call join carries its reason and ETA; a plain join and an unknown reason carry neither")
    void closeCall() {
        GiftNotices n = parse("""
            {"live":true,"seq":9,"messages":[],
             "joins":[
               {"seq":4,"joinId":"j-AbCdEfGh1234","uuid":"a","name":"Runner","reason":"close_call","etaMinutes":14},
               {"seq":5,"joinId":"j-AbCdEfGh5678","uuid":"b","name":"Viewer"},
               {"seq":6,"joinId":"j-AbCdEfGh9999","uuid":"c","name":"Odd","reason":"/op","etaMinutes":999}
             ]}""");
        assertTrue(n.joins().get(0).closeCall());
        assertEquals(14, n.joins().get(0).etaMinutes());
        assertFalse(n.joins().get(1).closeCall());
        assertEquals(0, n.joins().get(1).etaMinutes());
        assertFalse(n.joins().get(2).closeCall(), "only the relay's own reason is honoured");
        assertEquals(60, n.joins().get(2).etaMinutes(), "an absurd ETA is clamped");
    }

    @Test
    @DisplayName("only relay-shaped ids are gift ids")
    void ids() {
        assertTrue(LiveFeedClient.isGiftId("g-AbCd_Ef-1234"));
        assertFalse(LiveFeedClient.isGiftId("g-short"));
        assertFalse(LiveFeedClient.isGiftId("x-AbCdEfGh1234"));
        assertFalse(LiveFeedClient.isGiftId(null));
    }

    @Test
    @DisplayName("/offereye's viewer list keeps only player-shaped names, deduped and capped")
    void giftViewers() {
        var r = new LiveFeedClient.Result(200, JsonParser.parseString("""
            {"ok":true,"viewers":[{"name":"Steve"},{"name":"Alex_42"},{"name":"Steve"},
              {"name":"§cRed"},{"name":"a/b c"},{"name":""},{"nope":1},"x"]}""").getAsJsonObject(), null);
        assertEquals(java.util.List.of("Steve", "Alex_42", "Red"), LiveFeedClient.parseGiftViewers(r));
        StringBuilder many = new StringBuilder("{\"viewers\":[");
        for (int i = 0; i < 30; i++) many.append(i == 0 ? "" : ",").append("{\"name\":\"v").append(i).append("\"}");
        var big = new LiveFeedClient.Result(200, JsonParser.parseString(many + "]}").getAsJsonObject(), null);
        assertEquals(LiveFeedClient.MAX_GIFT_VIEWERS, LiveFeedClient.parseGiftViewers(big).size());
        assertTrue(LiveFeedClient.parseGiftViewers(new LiveFeedClient.Result(404, null, null)).isEmpty());
        assertTrue(LiveFeedClient.parseGiftViewers(new LiveFeedClient.Result(200,
            JsonParser.parseString("{\"viewers\":5}").getAsJsonObject(), null)).isEmpty());
    }

    @Test
    @DisplayName("a gift notice offered to everyone carries all=true; older notices read false")
    void giftOfferAll() {
        GiftNotices n = parse("""
            {"gifts":[
               {"seq":1,"giftId":"g-AbCdEfGh1234","target":"aaaaaaaabbbbccccddddeeeeeeeeeeee","from":"S","all":true},
               {"seq":2,"giftId":"g-AbCdEfGh5678","target":"aaaaaaaabbbbccccddddeeeeeeeeeeee","from":"S"},
               {"seq":3,"giftId":"g-AbCdEfGh9999","target":"aaaaaaaabbbbccccddddeeeeeeeeeeee","from":"S","all":"x"}
             ]}""");
        assertTrue(n.gifts().get(0).all());
        assertFalse(n.gifts().get(1).all());
        assertFalse(n.gifts().get(2).all(), "a non-boolean all is not an offer to everyone");
    }
}
