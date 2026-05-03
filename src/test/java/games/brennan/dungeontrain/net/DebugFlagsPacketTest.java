package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wire-format round-trip for {@link DebugFlagsPacket}. The packet grew
 * from 6 to 8 booleans when the chat-log toggles were added — these
 * tests pin the field order so a future regression that swaps two
 * neighbouring booleans (e.g. {@code chatTrainSpawn} ↔ {@code chatCollision})
 * fails immediately rather than silently scrambling client state.
 *
 * <p>The asymmetric-mix tests (only one true, alternating pattern) are
 * the load-bearing ones: an all-true or all-false round-trip would pass
 * even if writer / reader swapped two indices.</p>
 */
final class DebugFlagsPacketTest {

    @Test
    @DisplayName("round-trip preserves all-false default state")
    void roundTrip_allFalse() {
        DebugFlagsPacket original = new DebugFlagsPacket(
            false, false, false, false, false, false, false, false);
        DebugFlagsPacket decoded = roundTrip(original);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("round-trip preserves all-true state")
    void roundTrip_allTrue() {
        DebugFlagsPacket original = new DebugFlagsPacket(
            true, true, true, true, true, true, true, true);
        DebugFlagsPacket decoded = roundTrip(original);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("round-trip preserves only chatTrainSpawn=true (catches field-order swap)")
    void roundTrip_onlyChatTrainSpawn() {
        DebugFlagsPacket original = new DebugFlagsPacket(
            false, false, false, false, false, false, true, false);
        DebugFlagsPacket decoded = roundTrip(original);
        assertEquals(original, decoded);
        assertEquals(true, decoded.chatTrainSpawn());
        assertEquals(false, decoded.chatCollision());
    }

    @Test
    @DisplayName("round-trip preserves only chatCollision=true (catches field-order swap)")
    void roundTrip_onlyChatCollision() {
        DebugFlagsPacket original = new DebugFlagsPacket(
            false, false, false, false, false, false, false, true);
        DebugFlagsPacket decoded = roundTrip(original);
        assertEquals(original, decoded);
        assertEquals(false, decoded.chatTrainSpawn());
        assertEquals(true, decoded.chatCollision());
    }

    @Test
    @DisplayName("round-trip preserves alternating bit pattern (pins every position)")
    void roundTrip_alternating() {
        DebugFlagsPacket original = new DebugFlagsPacket(
            true, false, true, false, true, false, true, false);
        DebugFlagsPacket decoded = roundTrip(original);
        assertEquals(true, decoded.gapCubes());
        assertEquals(false, decoded.gapLine());
        assertEquals(true, decoded.nextSpawn());
        assertEquals(false, decoded.collision());
        assertEquals(true, decoded.hudDistance());
        assertEquals(false, decoded.manualSpawnMode());
        assertEquals(true, decoded.chatTrainSpawn());
        assertEquals(false, decoded.chatCollision());
    }

    @Test
    @DisplayName("round-trip preserves wireframe-on / chatlog-off (typical visual-only debug session)")
    void roundTrip_wireframesOn_chatLogsOff() {
        DebugFlagsPacket original = new DebugFlagsPacket(
            true, true, true, true, true, false, false, false);
        DebugFlagsPacket decoded = roundTrip(original);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("round-trip preserves wireframe-off / chatlog-on (typical chat-only debug session)")
    void roundTrip_wireframesOff_chatLogsOn() {
        DebugFlagsPacket original = new DebugFlagsPacket(
            false, false, false, false, false, false, true, true);
        DebugFlagsPacket decoded = roundTrip(original);
        assertEquals(original, decoded);
    }

    private static DebugFlagsPacket roundTrip(DebugFlagsPacket original) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        DebugFlagsPacket.STREAM_CODEC.encode(buf, original);
        return DebugFlagsPacket.STREAM_CODEC.decode(buf);
    }
}
