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
    @DisplayName("only relay-shaped ids are gift ids")
    void ids() {
        assertTrue(LiveFeedClient.isGiftId("g-AbCd_Ef-1234"));
        assertFalse(LiveFeedClient.isGiftId("g-short"));
        assertFalse(LiveFeedClient.isGiftId("x-AbCdEfGh1234"));
        assertFalse(LiveFeedClient.isGiftId(null));
    }
}
