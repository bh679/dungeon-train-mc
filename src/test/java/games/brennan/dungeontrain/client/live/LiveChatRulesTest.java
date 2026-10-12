package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveChatRulesTest {

    private static final UUID ME = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER = UUID.fromString("66666666-2222-3333-4444-555555555555");

    @Test
    @DisplayName("granted: the switch, not Kid mode, and streaming or a worn TV with someone live")
    void granted() {
        assertTrue(LiveChatRules.granted(true, false, true, false, false), "streaming");
        assertTrue(LiveChatRules.granted(true, false, false, true, true), "TV + someone live");
        assertFalse(LiveChatRules.granted(true, false, false, true, false), "TV but nobody live");
        assertFalse(LiveChatRules.granted(true, false, false, false, true), "no TV, not streaming");
        assertFalse(LiveChatRules.granted(false, false, true, true, true), "switch off");
        assertFalse(LiveChatRules.granted(true, true, true, true, true), "Kid mode wins");
    }

    @Test
    @DisplayName("shouldShow: Discord lines always; game lines unless mine or from a player in this world")
    void shouldShow() {
        ChatLine discord = new ChatLine(1, 0, "Viewer", null, "hi", false);
        ChatLine mine = new ChatLine(2, 0, "Me", LiveChatRules.undashed(ME), "hi", true);
        ChatLine other = new ChatLine(3, 0, "Other", LiveChatRules.undashed(OTHER), "hi", true);
        assertTrue(LiveChatRules.shouldShow(discord, ME, Set.of(ME, OTHER), Set.of("Me", "Other")));
        assertFalse(LiveChatRules.shouldShow(mine, ME, Set.of(), Set.of()));
        assertFalse(LiveChatRules.shouldShow(other, ME, Set.of(ME, OTHER), Set.of()), "already seen in vanilla chat");
        assertTrue(LiveChatRules.shouldShow(other, ME, Set.of(ME), Set.of()), "elsewhere: shown");
        assertTrue(LiveChatRules.shouldShow(mine, null, Set.of(), Set.of()), "no identity: shown");
        assertFalse(LiveChatRules.shouldShow(other, ME, Set.of(), Set.of("other")), "offline server: matched by name");
        ChatLine anon = new ChatLine(4, 0, "Other", null, "hi", true);
        assertFalse(LiveChatRules.shouldShow(anon, ME, Set.of(), Set.of("Other")), "no uuid: still matched by name");
    }

    @Test
    @DisplayName("outbound: commands and blanks are skipped, the rest trimmed and capped")
    void outbound() {
        assertNull(LiveChatRules.outbound(null));
        assertNull(LiveChatRules.outbound("   "));
        assertNull(LiveChatRules.outbound("/dungeontrain-options"));
        assertEquals("hello chat", LiveChatRules.outbound("  hello chat  "));
        assertEquals(LiveChatRules.MAX_CONTENT, LiveChatRules.outbound("x".repeat(LiveChatRules.MAX_CONTENT + 1)).length());
    }

    @Test
    @DisplayName("line: [Live] Name: text")
    void line() {
        ChatLine l = new ChatLine(1, 0, "Viewer", null, "hi there", false);
        assertEquals("chat.dungeontrain.live_chat.prefix Viewer: hi there", LiveChatRules.line(l).getString());
    }
}
