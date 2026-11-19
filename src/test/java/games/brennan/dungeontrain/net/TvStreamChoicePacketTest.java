package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TvStreamChoicePacketTest {

    @Test
    @DisplayName("the choice round-trips; anything but 1 or 2 reads as both")
    void roundTrip() {
        for (int v : new int[] {0, 1, 2}) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            TvStreamChoicePacket.STREAM_CODEC.encode(buf, new TvStreamChoicePacket(v));
            assertEquals(v, TvStreamChoicePacket.STREAM_CODEC.decode(buf).stream());
        }
        FriendlyByteBuf junk = new FriendlyByteBuf(Unpooled.buffer());
        junk.writeVarInt(7);
        assertEquals(0, TvStreamChoicePacket.STREAM_CODEC.decode(junk).stream());
    }
}
