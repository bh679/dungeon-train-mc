package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Wire round-trip for screen input: the input kind plus which pause it happened in, and how far in. */
final class ClientInputPacketTest {

    @Test
    @DisplayName("ClientInputPacket round-trips every pause kind")
    void roundTrip() {
        ClientInputPacket[] packets = {
            ClientInputPacket.unpaused(false),
            ClientInputPacket.unpaused(true),
            new ClientInputPacket(true, ClientInputPacket.PAUSE_GAME_MENU, 130_000),
            new ClientInputPacket(true, ClientInputPacket.PAUSE_SCREEN, Integer.MAX_VALUE),
        };
        for (ClientInputPacket packet : packets) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            ClientInputPacket.STREAM_CODEC.encode(buf, packet);
            assertEquals(packet, ClientInputPacket.STREAM_CODEC.decode(buf));
            assertEquals(0, buf.readableBytes());
        }
    }
}
