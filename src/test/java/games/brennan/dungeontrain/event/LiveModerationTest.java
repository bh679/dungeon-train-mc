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
        assertEquals("chat.dungeontrain.live.censored_final", key(LiveModeration.warning("chat", 4, LADDER)));
        assertEquals("chat.dungeontrain.live.censored_book", key(LiveModeration.warning("book_field", 2, LADDER)));
        assertEquals("chat.dungeontrain.live.censored_book_final", key(LiveModeration.warning("book", 9, LADDER)));
        Object[] args = ((TranslatableContents) LiveModeration.warning("chat", 2, LADDER).getContents()).getArgs();
        assertEquals(2, args[0]);
        assertEquals(3, args[1]);
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
}
