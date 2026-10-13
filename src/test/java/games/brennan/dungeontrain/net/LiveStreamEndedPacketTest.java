package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Wire round-trip for the two live-feed packets' enums; an unknown ordinal degrades to the safe value. */
final class LiveStreamEndedPacketTest {

    @Test
    @DisplayName("LiveStreamEndedPacket round-trips both reasons; a bad ordinal reads as FAILED")
    void endedRoundTrip() {
        for (LiveStreamEndedPacket.Reason r : LiveStreamEndedPacket.Reason.values()) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            LiveStreamEndedPacket.STREAM_CODEC.encode(buf, new LiveStreamEndedPacket(r));
            assertEquals(r, LiveStreamEndedPacket.STREAM_CODEC.decode(buf).reason());
        }
        FriendlyByteBuf bad = new FriendlyByteBuf(Unpooled.buffer());
        bad.writeVarInt(99);
        assertEquals(LiveStreamEndedPacket.Reason.FAILED, LiveStreamEndedPacket.STREAM_CODEC.decode(bad).reason());
    }

    @Test
    @DisplayName("LiveLifePacket round-trips a life id")
    void lifeRoundTrip() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        LiveLifePacket p = new LiveLifePacket("0123456789abcdef0123456789abcdef");
        LiveLifePacket.STREAM_CODEC.encode(buf, p);
        assertEquals(p, LiveLifePacket.STREAM_CODEC.decode(buf));
    }

    @Test
    @DisplayName("LiveStreamPacket round-trips every action including STOP_REMOVED")
    void streamRoundTrip() {
        for (LiveStreamPacket.Action a : LiveStreamPacket.Action.values()) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            LiveStreamPacket p = a == LiveStreamPacket.Action.START ? LiveStreamPacket.start("3f2a9c1e-0000-4000-8000-00000000abcd") : LiveStreamPacket.stop(a, "Dev2");
            LiveStreamPacket.STREAM_CODEC.encode(buf, p);
            assertEquals(p, LiveStreamPacket.STREAM_CODEC.decode(buf));
        }
    }
}
