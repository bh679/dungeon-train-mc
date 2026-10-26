package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatPage;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.LineTranslations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveFeedClientTranslationsParseTest {

    private static ChatPage parse(String json) {
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();
        return LiveFeedClient.parseChat(new LiveFeedClient.Result(200, body, null));
    }

    @Test
    @DisplayName("a page's translations name earlier lines; a line read with its own tr carries it inline")
    void translationsAndInlineTr() {
        ChatPage p = parse("""
            {"ok":true,"live":true,"seq":9,"messages":[
              {"seq":8,"author":"Alice","uuid":"aa","content":"the creeper got me","source":"game",
               "tr":{"zh_cn":"苦力怕\\u00a7c炸了我","de_de":"  ","xx":"bad key","en-US":"bad key too","ja_jp":42}}
            ],"translations":[
              {"line":5,"tr":{"zh_cn":"你好","pt_br":"oi"}},
              {"line":"six","tr":{"zh_cn":"x"}},
              {"line":7},
              {"line":4,"tr":{"zz":"nothing usable"}}
            ]}""");
        assertEquals(Map.of("zh_cn", "苦力怕炸了我"), p.lines().get(0).tr(), "cleaned like content; blank, bad-key and non-string values dropped");
        assertEquals(1, p.translations().size(), "only the entry with a numeric line and a usable map");
        LineTranslations t = p.translations().get(0);
        assertEquals(5, t.line());
        assertEquals(Map.of("zh_cn", "你好", "pt_br", "oi"), t.tr());
    }

    @Test
    @DisplayName("an older relay sends neither: nothing to show, nothing breaks")
    void absent() {
        ChatPage p = parse("""
            {"ok":true,"live":true,"seq":2,"messages":[{"seq":2,"author":"Bob","content":"hi","source":"discord"}]}""");
        assertNull(p.lines().get(0).tr());
        assertTrue(p.translations().isEmpty());
        assertTrue(ChatPage.EMPTY.translations().isEmpty());
    }

    @Test
    @DisplayName("a translation map is capped, not refused")
    void capped() {
        StringBuilder tr = new StringBuilder();
        for (int i = 0; i < LiveFeedClient.MAX_TRANSLATIONS + 5; i++) {
            if (i > 0) tr.append(',');
            tr.append('"').append((char) ('a' + i / 26)).append((char) ('a' + i % 26)).append("_xx\":\"t\"");
        }
        ChatPage p = parse("{\"seq\":1,\"messages\":[{\"seq\":1,\"author\":\"A\",\"content\":\"x\",\"source\":\"web\",\"tr\":{" + tr + "}}]}");
        assertEquals(LiveFeedClient.MAX_TRANSLATIONS, p.lines().get(0).tr().size());
    }

    @Test
    @DisplayName("the poll names the room and the reader's language; a bad language is left out")
    void chatPathWithLang() {
        assertEquals("/live/chat?since=5&room=second&lang=zh_cn", LiveFeedClient.chatPath(5, "second", "zh_cn"));
        assertEquals("/live/chat?lang=de_de", LiveFeedClient.chatPath(-1, null, "de_de"));
        assertEquals("/live/chat?since=5", LiveFeedClient.chatPath(5, null, "en-US&x=1"));
        assertEquals("/live/chat?since=5&room=main", LiveFeedClient.chatPath(5, "main"));
    }
}
