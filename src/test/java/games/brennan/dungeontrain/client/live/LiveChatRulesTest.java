package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.net.relay.LiveFeedClient.ChatLine;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Source;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Via;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
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
        ChatLine discord = new ChatLine(1, 0, "Viewer", null, "hi", Source.DISCORD);
        ChatLine mine = new ChatLine(2, 0, "Me", LiveChatRules.undashed(ME), "hi", Source.GAME);
        ChatLine other = new ChatLine(3, 0, "Other", LiveChatRules.undashed(OTHER), "hi", Source.GAME);
        ChatLine twitch = new ChatLine(5, 0, "Me", null, "hi", Source.TWITCH);
        assertTrue(LiveChatRules.shouldShow(twitch, ME, Set.of(ME), Set.of("Me")), "a Twitch viewer sharing my name is still shown");
        assertTrue(LiveChatRules.shouldShow(discord, ME, Set.of(ME, OTHER), Set.of("Me", "Other")));
        assertFalse(LiveChatRules.shouldShow(mine, ME, Set.of(), Set.of()));
        assertFalse(LiveChatRules.shouldShow(other, ME, Set.of(ME, OTHER), Set.of()), "already seen in vanilla chat");
        assertTrue(LiveChatRules.shouldShow(other, ME, Set.of(ME), Set.of()), "elsewhere: shown");
        assertTrue(LiveChatRules.shouldShow(mine, null, Set.of(), Set.of()), "no identity: shown");
        assertFalse(LiveChatRules.shouldShow(other, ME, Set.of(), Set.of("other")), "offline server: matched by name");
        ChatLine anon = new ChatLine(4, 0, "Other", null, "hi", Source.GAME);
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
    @DisplayName("outbound: a line tagging the dev is a ping to Brennan, not stream chat")
    void outboundSkipsDevMentions() {
        assertNull(LiveChatRules.outbound("@dev hi"));
        assertNull(LiveChatRules.outbound("hey @BrennanHatton look"));
        assertNull(LiveChatRules.outbound("@开发者 你好"));
        assertNull(LiveChatRules.outbound("me@devnull"), "substring, as DiscordPresence matches (it pings too)");
        assertEquals("dev is cool", LiveChatRules.outbound("dev is cool"));
    }

    @Test
    @DisplayName("shouldShow: another world's dev ping is hidden; Discord/Twitch lines are not filtered")
    void shouldShowHidesGameDevMentions() {
        ChatLine gamePing = new ChatLine(1, 0, "Other", LiveChatRules.undashed(OTHER), "@DEV help", Source.GAME);
        ChatLine discordPing = new ChatLine(2, 0, "Viewer", null, "@dev hi", Source.DISCORD);
        assertFalse(LiveChatRules.shouldShow(gamePing, ME, Set.of(), Set.of()));
        assertTrue(LiveChatRules.shouldShow(discordPing, ME, Set.of(), Set.of()));
    }

    @Test
    @DisplayName("clean: § codes, control characters and line breaks go; the rest is stripped and capped")
    void clean() {
        assertEquals("", LiveChatRules.clean(null, 10));
        assertEquals("hi there", LiveChatRules.clean("§ahi §kthere§r", 100));
        assertEquals("one[Dev] two", LiveChatRules.clean("one\n[Dev] two\r", 100), "a forged second line folds into one");
        assertEquals("ab", LiveChatRules.clean("a\u0000\u0007\u007fb\t", 100));
        assertEquals("tail", LiveChatRules.clean("tail§", 100), "a lone trailing § is dropped");
        assertEquals("héllo 🎉", LiveChatRules.clean("  héllo 🎉  ", 100), "unicode is untouched");
        assertEquals(LiveChatRules.MAX_AUTHOR, LiveChatRules.clean("n".repeat(99), LiveChatRules.MAX_AUTHOR).length());
        assertEquals(LiveChatRules.MAX_CONTENT, LiveChatRules.clean("x".repeat(600), LiveChatRules.MAX_CONTENT).length());
    }

    @Test
    @DisplayName("cursor: joins at the head, grows, ignores a failed poll, and follows a relay restart")
    void cursorFollowsRelayRestart() {
        assertEquals(42, LiveChatRules.nextCursor(-1, 42), "join");
        assertEquals(50, LiveChatRules.nextCursor(42, 50), "grows");
        assertEquals(50, LiveChatRules.nextCursor(50, -1), "failed poll leaves it alone");
        assertEquals(-1, LiveChatRules.nextCursor(-1, -1));
        assertEquals(3, LiveChatRules.nextCursor(400, 3), "relay redeployed: ring restarts at 1");
        assertTrue(LiveChatRules.restarted(400, 3));
        assertFalse(LiveChatRules.restarted(400, 400));
        assertFalse(LiveChatRules.restarted(-1, 3), "not yet joined");
        assertFalse(LiveChatRules.restarted(400, -1), "a failed poll is not a restart");
    }

    @Test
    @DisplayName("line: a forged author or content still prints as one plain line")
    void lineIsPlain() {
        ChatLine l = new ChatLine(1, 0, "Vi§cewer\n[Dev]", null, "hi\n§kthere", Source.DISCORD);
        assertEquals("chat.dungeontrain.live_chat.prefix \uE003 Viewer[Dev]: hithere", LiveChatRules.line(l).getString());
        for (var sib : LiveChatRules.line(l).getSiblings()) {
            assertNull(sib.getStyle().getClickEvent());
            assertNull(sib.getStyle().getHoverEvent());
        }
    }

    @Test
    @DisplayName("line: [Live] <icon> Name: text, the icon in the icon font, the name coloured by room")
    void line() {
        ChatLine l = new ChatLine(1, 0, "Viewer", null, "hi there", Source.DISCORD);
        assertEquals("chat.dungeontrain.live_chat.prefix \uE003 Viewer: hi there", LiveChatRules.line(l).getString());
        ChatLine t = new ChatLine(2, 0, "Twitcher", null, "yo", Source.TWITCH);
        assertEquals("chat.dungeontrain.live_chat.prefix \uE004 Twitcher: yo", LiveChatRules.line(t).getString());
        var glyph = LiveChatRules.line(t).getSiblings().get(1).getStyle();
        assertEquals(LiveChatRules.ICON_FONT, glyph.getFont());
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.WHITE), glyph.getColor(), "white, so the glyph keeps its colours");
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.LIGHT_PURPLE), LiveChatRules.line(t).getSiblings().get(2).getStyle().getColor());
        ChatLine o = new ChatLine(3, 0, "Bot", null, "?", Source.OTHER);
        assertEquals("chat.dungeontrain.live_chat.prefix Bot: ?", LiveChatRules.line(o).getString(), "an unknown room has no icon");
        assertEquals(ChatFormatting.AQUA, LiveChatRules.nameColour(Source.WEB));
        assertEquals(ChatFormatting.AQUA, LiveChatRules.nameColour(Source.DISCORD));
        assertEquals(ChatFormatting.AQUA, LiveChatRules.nameColour(Source.OTHER));
        assertEquals(ChatFormatting.LIGHT_PURPLE, LiveChatRules.nameColour(Source.TWITCH));
        assertEquals(ChatFormatting.GREEN, LiveChatRules.nameColour(Source.GAME));
    }

    @Test
    @DisplayName("icon: Live Eye for the streamer, TV for other game lines (and old relays), then globe / Discord / Twitch")
    void icon() {
        assertEquals(LiveChatRules.Icon.EYE, LiveChatRules.icon(new ChatLine(1, 0, "S", null, "x", Source.GAME, null, Via.EYE)));
        assertEquals(LiveChatRules.Icon.TV, LiveChatRules.icon(new ChatLine(1, 0, "T", null, "x", Source.GAME, null, Via.TV)));
        assertEquals(LiveChatRules.Icon.TV, LiveChatRules.icon(new ChatLine(1, 0, "T", null, "x", Source.GAME)));
        assertEquals(LiveChatRules.Icon.WEB, LiveChatRules.icon(new ChatLine(1, 0, "W", null, "x", Source.WEB)));
        assertEquals(LiveChatRules.Icon.DISCORD, LiveChatRules.icon(new ChatLine(1, 0, "D", null, "x", Source.DISCORD)));
        assertEquals(LiveChatRules.Icon.TWITCH, LiveChatRules.icon(new ChatLine(1, 0, "T", null, "x", Source.TWITCH)));
        assertNull(LiveChatRules.icon(new ChatLine(1, 0, "O", null, "x", Source.OTHER)));
    }

    @Test
    @DisplayName("parse: the relay's web source and via field")
    void parse() {
        assertEquals(Source.WEB, Source.parse("web"));
        assertEquals(Via.EYE, Via.parse("eye"));
        assertEquals(Via.TV, Via.parse("tv"));
        assertNull(Via.parse(null));
        assertNull(Via.parse("telescope"));
    }

    @Test
    @DisplayName("isVoidWhisper: DiscordPresence's auto-response keys, grey or not; nothing else")
    void isVoidWhisper() {
        assertTrue(LiveChatRules.isVoidWhisper(Component.translatable("discordpresence.autoresponse.alone.0", "Steve")));
        assertTrue(LiveChatRules.isVoidWhisper(Component.translatable("discordpresence.autoresponse.group.2", "Steve")));
        assertTrue(LiveChatRules.isVoidWhisper(Component.translatable("discordpresence.autoresponse.mention_hint.7", "Steve", "@dev")
            .copy().withStyle(ChatFormatting.GRAY)));
        assertFalse(LiveChatRules.isVoidWhisper(Component.translatable("discordpresence.chattag.dev")));
        assertFalse(LiveChatRules.isVoidWhisper(Component.translatable(LiveChatRules.CONNECTED_KEY)));
        assertFalse(LiveChatRules.isVoidWhisper(Component.literal("Steve whispers into the void, will anyone answer?")));
        assertFalse(LiveChatRules.isVoidWhisper(null));
    }
}
