package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Wire round-trip for the train's plot readout offset, known and unknown. */
final class PlotReadoutOffsetPacketTest {

    @Test
    @DisplayName("round-trips a recorded offset, negatives included")
    void recorded() {
        PlotReadoutOffsetPacket p = new PlotReadoutOffsetPacket(true, 20_480_700L, -20_495_365L);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PlotReadoutOffsetPacket.STREAM_CODEC.encode(buf, p);
        assertEquals(p, PlotReadoutOffsetPacket.STREAM_CODEC.decode(buf));
    }

    @Test
    @DisplayName("round-trips 'no offset recorded'")
    void unknown() {
        PlotReadoutOffsetPacket p = new PlotReadoutOffsetPacket(false, 0L, 0L);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PlotReadoutOffsetPacket.STREAM_CODEC.encode(buf, p);
        assertEquals(p, PlotReadoutOffsetPacket.STREAM_CODEC.decode(buf));
    }
}
