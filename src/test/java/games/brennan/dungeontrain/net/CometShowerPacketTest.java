package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Wire round-trip for a comet shower: its direction and how long it lasts. */
final class CometShowerPacketTest {

    @Test
    void showerRoundTrips() {
        CometShowerPacket shower = new CometShowerPacket(-0.48F, -0.8F, 0.36F, 500);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        CometShowerPacket.STREAM_CODEC.encode(buf, shower);
        assertEquals(shower, CometShowerPacket.STREAM_CODEC.decode(buf));
        assertEquals(0, buf.readableBytes());
    }
}
