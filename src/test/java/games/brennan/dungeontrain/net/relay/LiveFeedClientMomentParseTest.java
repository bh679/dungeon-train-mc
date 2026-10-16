package games.brennan.dungeontrain.net.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.client.live.LiveChatRules;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatLine;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatPage;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.MomentKind;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveFeedClientMomentParseTest {

    private static ChatPage parse(String json) {
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();
        return LiveFeedClient.parseChat(new LiveFeedClient.Result(200, body, null));
    }

    @Test
    @DisplayName("a streamer's moment rides on its game line; an unknown kind or none is a plain line")
    void moments() {
        ChatPage p = parse("""
            {"ok":true,"live":true,"seq":4,"messages":[
              {"seq":1,"author":"Alice","uuid":"aa","content":"🏆 earned Stone Age","source":"game","via":"eye",
               "moment":{"kind":"advancement","title":"Stone Age"}},
              {"seq":2,"author":"Alice","uuid":"aa","content":"📷 took a photo","source":"game","via":"eye",
               "moment":{"kind":"photo"},"image":"https://cdn.discordapp.com/x.png"},
              {"seq":3,"author":"Alice","uuid":"aa","content":"📖 wrote Diary","source":"game","via":"eye",
               "moment":{"kind":"book","title":"Diary\\u00a7c","excerpt":"Day one.\\nThe train"}},
              {"seq":4,"author":"Alice","uuid":"aa","content":"hi","source":"game","moment":{"kind":"dance"}}
            ]}""");
        assertEquals(4, p.lines().size());
        assertEquals(MomentKind.ADVANCEMENT, p.lines().get(0).moment().kind());
        assertEquals("Stone Age", p.lines().get(0).moment().title());
        assertEquals(MomentKind.PHOTO, p.lines().get(1).moment().kind());
        assertEquals("", p.lines().get(1).moment().title());
        assertEquals("Diary", p.lines().get(2).moment().title(), "§ codes are cleaned");
        assertEquals("Day one.The train", p.lines().get(2).moment().excerpt(), "line breaks are cleaned");
        assertNull(p.lines().get(3).moment(), "a newer relay's kind falls back to the plain line");
    }

    @Test
    @DisplayName("a moment line reads 'Name <translated moment>', a book's first lines on hover")
    void rendering() {
        ChatLine adv = new ChatLine(1, 0, "Alice", "aa", "x", LiveFeedClient.Source.GAME, null, LiveFeedClient.Via.EYE, null,
            new LiveFeedClient.Moment(MomentKind.ADVANCEMENT, "Stone Age", ""));
        assertEquals("chat.dungeontrain.live_chat.prefix  Alice chat.dungeontrain.live_moment.advancement",
            LiveChatRules.line(adv).getString());
        ChatLine book = new ChatLine(2, 0, "Alice", "aa", "x", LiveFeedClient.Source.GAME, null, LiveFeedClient.Via.EYE, null,
            new LiveFeedClient.Moment(MomentKind.BOOK, "Diary", "Day one"));
        Component body = LiveChatRules.momentBody(book.moment());
        HoverEvent hover = body.getStyle().getHoverEvent();
        assertNotNull(hover);
        assertEquals("Day one", hover.getValue(HoverEvent.Action.SHOW_TEXT).getString());
        assertTrue(LiveChatRules.momentBody(adv.moment()).getStyle().getHoverEvent() == null);
    }
}
