package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Wire round-trip for comets: launched, landed (with where) and burnt out. */
final class CometPacketTest {

    private static CometPacket roundTrip(CometPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        CometPacket.STREAM_CODEC.encode(buf, packet);
        CometPacket back = CometPacket.STREAM_CODEC.decode(buf);
        assertEquals(0, buf.readableBytes());
        return back;
    }

    @Test
    void launchedRoundTrips() {
        CometPacket launched = CometPacket.launched(5, 198330.5, 140.0, -12.25, 0.5, -1.0, 0.25);
        assertEquals(launched, roundTrip(launched));
    }

    @Test
    void landedKeepsWhereItHit() {
        CometPacket landed = CometPacket.landed(6, 198340.0, 79.5, 3.0);
        assertEquals(landed, roundTrip(landed));
    }

    @Test
    void burntOutCarriesNoPosition() {
        assertEquals(CometPacket.ended(7), roundTrip(CometPacket.ended(7)));
    }
}
