package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire-format round trips for the Stages tab's model ask and answer. */
final class StagePreviewPacketTest {

    @Test
    @DisplayName("the ask carries stage, carriage and seed")
    void request() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        StagePreviewRequestPacket.STREAM_CODEC.encode(buf, new StagePreviewRequestPacket("desert", "standard", -42L));
        StagePreviewRequestPacket decoded = StagePreviewRequestPacket.STREAM_CODEC.decode(buf);
        assertFalse(buf.isReadable());
        assertEquals("desert", decoded.stageId());
        assertEquals("standard", decoded.carriageId());
        assertEquals(-42L, decoded.seed());
    }

    @Test
    @DisplayName("the answer carries the bytes back under the same key; none() is found=false with no bytes")
    void reply() {
        byte[] bytes = {1, 2, 3, 4};
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        StagePreviewPacket.STREAM_CODEC.encode(buf, new StagePreviewPacket("desert", "standard", 7L, true, bytes));
        StagePreviewPacket decoded = StagePreviewPacket.STREAM_CODEC.decode(buf);
        assertFalse(buf.isReadable());
        assertTrue(decoded.found());
        assertArrayEquals(bytes, decoded.template());
        assertEquals(7L, decoded.seed());
        StagePreviewPacket none = StagePreviewPacket.none(new StagePreviewRequestPacket("desert", "standard", 7L));
        assertFalse(none.found());
        assertEquals(0, none.template().length);
    }
}
