package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Wire round-trip for the client's "I masked a word" report; an unknown ordinal reads as plain CHAT. */
final class LiveModerationStrikePacketTest {

    @Test
    @DisplayName("every source round-trips; a bad ordinal degrades to CHAT")
    void roundTrip() {
        for (LiveModerationStrikePacket.Source s : LiveModerationStrikePacket.Source.values()) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            LiveModerationStrikePacket p = new LiveModerationStrikePacket(s, "you zorp", java.util.List.of("zorp"));
            LiveModerationStrikePacket.STREAM_CODEC.encode(buf, p);
            assertEquals(p, LiveModerationStrikePacket.STREAM_CODEC.decode(buf));
        }
        FriendlyByteBuf bad = new FriendlyByteBuf(Unpooled.buffer());
        bad.writeVarInt(42);
        bad.writeUtf("x");
        bad.writeVarInt(0);
        assertEquals(LiveModerationStrikePacket.Source.CHAT, LiveModerationStrikePacket.STREAM_CODEC.decode(bad).source());
    }

    @Test
    @DisplayName("evidence is capped: text to 500 chars, terms to 20")
    void caps() {
        String longText = "a".repeat(900);
        java.util.List<String> many = java.util.stream.IntStream.range(0, 40).mapToObj(i -> "t" + i).toList();
        LiveModerationStrikePacket p = new LiveModerationStrikePacket(LiveModerationStrikePacket.Source.CHAT, longText, many);
        assertEquals(500, p.text().length());
        assertEquals(20, p.terms().size());
    }

    @Test
    @DisplayName("the relay name is the lower-case constant")
    void relayNames() {
        assertEquals("chat_field", LiveModerationStrikePacket.Source.CHAT_FIELD.relayName());
        assertEquals("book_field", LiveModerationStrikePacket.Source.BOOK_FIELD.relayName());
    }
}
