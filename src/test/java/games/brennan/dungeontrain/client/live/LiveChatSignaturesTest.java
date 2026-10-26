package games.brennan.dungeontrain.client.live;

import net.minecraft.network.chat.MessageSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @DisplayName("a line's translation part is its own signature, and goes with the line when all are taken")
    void parts() {
        LiveChatSignatures s = new LiveChatSignatures(5L);
        MessageSignature line = s.sign(3);
        MessageSignature under = s.signPart(3, 1);
        assertNotEquals(line, under);
        assertEquals(java.util.List.of(line, under), s.takeAll(3));
        assertEquals(java.util.List.of(), s.takeAll(3), "taken once");
        s.sign(4);
        assertEquals(1, s.takeAll(4).size(), "a line without a part");
        assertEquals(s.signPart(8, 0), s.sign(8), "part 0 is the line itself");
    }

    @Test
    @DisplayName("isNewest holds only when the newest chat line is that seq's own line, and forgets nothing")
    void isNewest() {
        LiveChatSignatures s = new LiveChatSignatures(9L);
        MessageSignature line = s.sign(10);
        MessageSignature other = s.sign(11);
        MessageSignature under = s.signPart(10, 1);
        assertTrue(s.isNewest(10, line));
        assertFalse(s.isNewest(10, other), "another live line came after it");
        assertFalse(s.isNewest(10, null), "an unsigned line (server chat, a notice) came after it");
        assertFalse(s.isNewest(10, under), "its own translation is not the line");
        assertFalse(s.isNewest(12, line), "never printed");
        assertFalse(new LiveChatSignatures(10L).isNewest(10, line), "another session's line");
        assertEquals(line, s.take(10), "peeking kept it");
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
