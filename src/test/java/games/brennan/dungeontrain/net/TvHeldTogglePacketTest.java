package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Wire round-trip for the held-TV toggle: one byte, both hands. */
final class TvHeldTogglePacketTest {

    @Test
    @DisplayName("TvHeldTogglePacket round-trips both hands")
    void roundTrip() {
        for (InteractionHand hand : InteractionHand.values()) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            TvHeldTogglePacket.STREAM_CODEC.encode(buf, new TvHeldTogglePacket(hand));
            assertEquals(1, buf.readableBytes());
            assertEquals(hand, TvHeldTogglePacket.STREAM_CODEC.decode(buf).hand());
        }
    }
}
