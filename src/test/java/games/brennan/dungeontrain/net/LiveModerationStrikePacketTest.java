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
            LiveModerationStrikePacket.STREAM_CODEC.encode(buf, new LiveModerationStrikePacket(s));
            assertEquals(s, LiveModerationStrikePacket.STREAM_CODEC.decode(buf).source());
        }
        FriendlyByteBuf bad = new FriendlyByteBuf(Unpooled.buffer());
        bad.writeVarInt(42);
        assertEquals(LiveModerationStrikePacket.Source.CHAT, LiveModerationStrikePacket.STREAM_CODEC.decode(bad).source());
    }

    @Test
    @DisplayName("the relay name is the lower-case constant")
    void relayNames() {
        assertEquals("chat_field", LiveModerationStrikePacket.Source.CHAT_FIELD.relayName());
        assertEquals("book_field", LiveModerationStrikePacket.Source.BOOK_FIELD.relayName());
    }
}
