package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Wire-format round trip for the undo/redo labels the X menu's history buttons read. */
final class EditorHistoryPacketTest {

    private static EditorHistoryPacket roundTrip(EditorHistoryPacket original) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        EditorHistoryPacket.STREAM_CODEC.encode(buf, original);
        EditorHistoryPacket decoded = EditorHistoryPacket.STREAM_CODEC.decode(buf);
        assertFalse(buf.isReadable(), "decode must consume exactly what encode wrote");
        return decoded;
    }

    @Test
    @DisplayName("both labels survive the round trip")
    void roundTrip() {
        EditorHistoryPacket decoded = roundTrip(
            new EditorHistoryPacket("Place — carriages/pen", "Clear — contents/armor5"));
        assertEquals("Place — carriages/pen", decoded.undoLabel());
        assertEquals("Clear — contents/armor5", decoded.redoLabel());
    }

    @Test
    @DisplayName("an empty stack travels as an empty string, and null normalises to one")
    void empty() {
        EditorHistoryPacket decoded = roundTrip(EditorHistoryPacket.empty());
        assertEquals("", decoded.undoLabel());
        assertEquals("", decoded.redoLabel());
        assertEquals("", new EditorHistoryPacket(null, null).undoLabel());
        assertEquals("", new EditorHistoryPacket(null, null).redoLabel());
    }
}
