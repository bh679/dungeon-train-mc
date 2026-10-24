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
