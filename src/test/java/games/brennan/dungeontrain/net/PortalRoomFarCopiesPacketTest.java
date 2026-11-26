package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalRoomFarCopiesPacketTest {

    private static PortalRoomFarCopiesPacket sample() {
        return new PortalRoomFarCopiesPacket(PortalRoomFarCopiesPacket.MODE_REPETITION,
            -120, -60, 40, 18, 9, 22,
            -210, -60, -70, 9, -51, 150,
            List.of(new PortalRoomFarCopiesPacket.TileRef(0, 0), new PortalRoomFarCopiesPacket.TileRef(-3, 2)),
            List.of(new PortalRoomFarCopiesPacket.Box(-130, -61, 40, -121, -50, 45)));
    }

    @Test
    void roundTripsEveryField() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PortalRoomFarCopiesPacket.STREAM_CODEC.encode(buf, sample());
        assertEquals(sample(), PortalRoomFarCopiesPacket.STREAM_CODEC.decode(buf));
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void noneRoundTripsAndAppliesNowhere() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PortalRoomFarCopiesPacket.STREAM_CODEC.encode(buf, PortalRoomFarCopiesPacket.none());
        PortalRoomFarCopiesPacket read = PortalRoomFarCopiesPacket.STREAM_CODEC.decode(buf);
        assertTrue(read.isNone());
        assertFalse(read.contains(0, 0, 0));
    }

    @Test
    void containsIsInclusiveOfTheLastBlock() {
        PortalRoomFarCopiesPacket p = sample();
        assertTrue(p.contains(9.5, -51.5, 150.5));
        assertFalse(p.contains(10.0, -55, 0));
    }

    @Test
    void refusesAnOversizedList() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        for (int i = 0; i < 13; i++) buf.writeVarInt(1);
        buf.writeVarInt(PortalRoomFarCopiesPacket.MAX_ENTRIES + 1);
        assertThrows(IllegalArgumentException.class, () -> PortalRoomFarCopiesPacket.STREAM_CODEC.decode(buf));
    }
}
