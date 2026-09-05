package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Wire-format round-trip for {@link TrainDebugSyncPacket}.
 *
 * <p>The denied case is the one that matters: an ungranted client must not be sent the world's
 * generation seed at all, so the packet writes a single {@code false} and stops. A reader that
 * kept reading would both overrun and imply the seed was on the wire.</p>
 */
final class TrainDebugSyncPacketTest {

    @Test
    @DisplayName("granted round-trip preserves expiry and seed")
    void roundTrip_granted() {
        TrainDebugSyncPacket original = new TrainDebugSyncPacket(true, 1790000000000L, -4127880351L);
        assertEquals(original, roundTrip(original));
    }

    @Test
    @DisplayName("a forever grant (expiry 0) round-trips as granted")
    void roundTrip_forever() {
        TrainDebugSyncPacket decoded = roundTrip(new TrainDebugSyncPacket(true, 0L, 99L));
        assertEquals(0L, decoded.expiresAtMs());
        assertEquals(99L, decoded.seed());
    }

    @Test
    @DisplayName("denied carries no seed on the wire")
    void roundTrip_denied_writesOnlyTheFlag() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        TrainDebugSyncPacket.denied().encode(buf);
        assertEquals(1, buf.writerIndex(), "denied must be a single boolean — no seed on the wire");

        TrainDebugSyncPacket decoded = TrainDebugSyncPacket.decode(buf);
        assertFalse(decoded.permitted());
        assertEquals(0L, decoded.seed());
    }

    private static TrainDebugSyncPacket roundTrip(TrainDebugSyncPacket original) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(buf);
        return TrainDebugSyncPacket.decode(buf);
    }
}
