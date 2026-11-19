package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.moderation.LiveModerationLadder;
import games.brennan.dungeontrain.moderation.LiveWordList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The warning names the step; the cached verdict decides who may wear a Live Eye. */
final class LiveModerationTest {

    private static final LiveWordList.Ladder LADDER = new LiveWordList.Ladder(4, 3, List.of(30, 90, 0), 30);

    private static String key(Component c) {
        return ((TranslatableContents) c.getContents()).getKey();
    }

    @Test
    @DisplayName("warnings count up to the ladder's last one; the removal step has its own line; books have theirs")
    void warningLines() {
        assertEquals("chat.dungeontrain.live.censored", key(LiveModeration.warning("chat", 1, LADDER)));
        assertEquals("chat.dungeontrain.live.censored", key(LiveModeration.warning("chat", 3, LADDER)));
        assertNull(LiveModeration.warning("chat", 4, LADDER));
        assertNull(LiveModeration.warning("book", 9, LADDER));
        assertEquals("chat.dungeontrain.live.censored_book", key(LiveModeration.warning("book_field", 2, LADDER)));
        Object[] args = ((TranslatableContents) LiveModeration.warning("chat", 2, LADDER).getContents()).getArgs();
        assertEquals(2, args[0]);
        assertEquals(3, args[1]);
    }

    @Test
    @DisplayName("the relay's answer to an appeal maps to one chat line each")
    void appealLines() {
        assertEquals("command.dungeontrain.live_appeal.received", LiveModeration.appealLineKey(200, "{\"ok\":true}"));
        assertEquals("command.dungeontrain.live_appeal.not_penalised", LiveModeration.appealLineKey(409, "{\"error\":\"not_banned\"}"));
        assertEquals("command.dungeontrain.live_appeal.pending", LiveModeration.appealLineKey(409, "{\"error\":\"appeal_pending\"}"));
        assertEquals("command.dungeontrain.live_appeal.denied", LiveModeration.appealLineKey(409, "{\"error\":\"appeal_denied\"}"));
        assertEquals("command.dungeontrain.live_appeal.failed", LiveModeration.appealLineKey(500, "nope"));
    }

    @Test
    @DisplayName("a cached timed ban holds until it lapses; a permanent one never does")
    void cachedBan() {
        UUID p = UUID.randomUUID();
        assertFalse(LiveModeration.isBanned(p));
        LiveModeration.apply(p, LiveModerationLadder.parse("{\"ok\":true,\"strikes\":0,\"removals\":3,\"banned\":true,\"remainingSec\":1}"), System.currentTimeMillis());
        assertTrue(LiveModeration.isBanned(p));
        LiveModeration.apply(p, LiveModerationLadder.parse("{\"ok\":true,\"strikes\":0,\"removals\":3,\"banned\":true,\"remainingSec\":1}"), System.currentTimeMillis() - 5000);
        assertFalse(LiveModeration.isBanned(p));
        LiveModeration.apply(p, LiveModerationLadder.parse("{\"ok\":true,\"strikes\":0,\"removals\":5,\"banned\":true,\"permanent\":true}"), 0);
        assertTrue(LiveModeration.isBanned(p));
        assertEquals(Long.MAX_VALUE, LiveModeration.banRemainingSec(p));
    }

    @Test
    @DisplayName("the burn line never makes up a length: no cached ban (the relay refused anyway) names none")
    void bannedBurnKeys() {
        assertEquals("chat.dungeontrain.live.burns_banned_unknown", LiveModeration.bannedBurnKey(0));
        assertEquals("chat.dungeontrain.live.burns_banned_permanent", LiveModeration.bannedBurnKey(Long.MAX_VALUE));
        assertEquals("chat.dungeontrain.live.burns_banned", LiveModeration.bannedBurnKey(3600));
        // A clean cache is what the relay-refused path sees before its status fetch lands.
        assertEquals("chat.dungeontrain.live.burns_banned_unknown",
            LiveModeration.bannedBurnKey(LiveModeration.banRemainingSec(UUID.randomUUID())));
    }

    @Test
    @DisplayName("a mute stops chat but not the Live Eye; either one is something to appeal")
    void muteIsPenaltyButNotBan() {
        UUID p = UUID.randomUUID();
        LiveModerationLadder.Verdict v = LiveModerationLadder.parse(
            "{\"ok\":true,\"strikes\":0,\"action\":\"mute\",\"muted\":true,\"muteRemainingSec\":900}");
        LiveModeration.apply(p, v, System.currentTimeMillis());
        assertTrue(LiveModeration.isMuted(p));
        assertFalse(LiveModeration.isBanned(p));
        assertTrue(LiveModeration.isPenalised(p));
        assertTrue(LiveModeration.muteRemainingSec(p) <= 900);
        assertFalse(LiveModeration.isPenalised(UUID.randomUUID()));
    }

    @Test
    @DisplayName("Free Play reports no strikes, except on a dev build")
    void freePlaySkipsStrikes() {
        assertTrue(LiveModeration.skipsStrikes(false, true));
        assertFalse(LiveModeration.skipsStrikes(false, false));
        assertFalse(LiveModeration.skipsStrikes(true, true));
    }
}
