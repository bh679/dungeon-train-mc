package games.brennan.dungeontrain.client.live;

import net.minecraft.network.chat.MessageSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LiveChatSignaturesTest {

    @Test
    @DisplayName("each seq gets its own vanilla-sized signature, handed back once by take")
    void signAndTake() {
        LiveChatSignatures s = new LiveChatSignatures(42L);
        MessageSignature a = s.sign(1);
        MessageSignature b = s.sign(2);
        assertEquals(256, a.bytes().length);
        assertNotEquals(a, b);
        assertEquals(a, s.take(1));
        assertNull(s.take(1), "taken once");
        assertNull(s.take(99), "never printed");
        assertNotEquals(a, new LiveChatSignatures(43L).sign(1), "the salt separates sessions");
    }

    @Test
    @DisplayName("only the newest MAX lines are kept; clear forgets them all")
    void bounded() {
        LiveChatSignatures s = new LiveChatSignatures(7L);
        for (long seq = 1; seq <= LiveChatSignatures.MAX + 10; seq++) s.sign(seq);
        assertEquals(LiveChatSignatures.MAX, s.size());
        assertNull(s.take(1), "the oldest fell out");
        s.clear();
        assertEquals(0, s.size());
    }
}
