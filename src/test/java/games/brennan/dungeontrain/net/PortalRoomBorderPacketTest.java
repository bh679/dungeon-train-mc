package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire round-trip for a World Border room's walls, and the "no border" sentinel. */
final class PortalRoomBorderPacketTest {

    private static PortalRoomBorderPacket roundTrip(PortalRoomBorderPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PortalRoomBorderPacket.STREAM_CODEC.encode(buf, packet);
        PortalRoomBorderPacket back = PortalRoomBorderPacket.STREAM_CODEC.decode(buf);
        assertEquals(0, buf.readableBytes());
        return back;
    }

    @Test
    void roomBoxRoundTrips() {
        // Twin space sits far below the world, so negative Y must survive the varints.
        PortalRoomBorderPacket box = new PortalRoomBorderPacket(198330, -1984, -12, 198346, -1970, 3);
        assertEquals(box, roundTrip(box));
        assertTrue(box.present());
    }

    @Test
    void noneIsNotABorder() {
        assertEquals(PortalRoomBorderPacket.none(), roundTrip(PortalRoomBorderPacket.none()));
        assertFalse(PortalRoomBorderPacket.none().present());
    }
}
