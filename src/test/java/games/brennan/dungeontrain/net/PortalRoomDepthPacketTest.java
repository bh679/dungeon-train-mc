package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format round-trip for {@link PortalRoomDepthPacket}, and the box test the client runs every
 * frame to decide whether the debug screen is lying.
 *
 * <p>The negative figures are the load-bearing part: a twin corridor lives well under the world, so
 * every bound this packet carries is normally negative and the shift that hides them is normally a
 * large positive. An encoder that could not carry those would disguise nothing at all.</p>
 */
final class PortalRoomDepthPacketTest {

    /** A structure in the basement of a world whose train runs at 78: floor at -102, shift +180. */
    private static final PortalRoomDepthPacket BASEMENT =
        new PortalRoomDepthPacket(96, -104, -80, 224, -80, -48, 180);

    @Test
    @DisplayName("round-trip preserves negative bounds and a positive shift")
    void roundTrip_basement() {
        assertEquals(BASEMENT, roundTrip(BASEMENT));
        assertTrue(roundTrip(BASEMENT).applies());
    }

    @Test
    @DisplayName("round-trip preserves an attic's negative shift")
    void roundTrip_attic() {
        PortalRoomDepthPacket attic =
            new PortalRoomDepthPacket(96, 300, -80, 224, 324, -48, -222);
        assertEquals(attic, roundTrip(attic));
        assertEquals(-222, roundTrip(attic).yShift());
    }

    @Test
    @DisplayName("none() describes no structure and contains nothing")
    void none_appliesToNothing() {
        assertFalse(PortalRoomDepthPacket.none().applies());
        assertFalse(PortalRoomDepthPacket.none().contains(0.0, 0.0, 0.0));
    }

    @Test
    @DisplayName("the box is inclusive at its edges and false everywhere outside")
    void contains_box() {
        assertTrue(BASEMENT.contains(160.0, -96.0, -64.0));
        assertTrue(BASEMENT.contains(96.0, -104.0, -80.0));
        assertTrue(BASEMENT.contains(224.0, -80.0, -48.0));
        // One step out of each face — the corridor mouth, the lane above, the room's far wall.
        assertFalse(BASEMENT.contains(95.9, -96.0, -64.0));
        assertFalse(BASEMENT.contains(160.0, -79.9, -64.0));
        assertFalse(BASEMENT.contains(160.0, -96.0, -47.9));
        // And on the train, which is the position that must never be disguised.
        assertFalse(BASEMENT.contains(160.0, 78.0, -64.0));
    }

    private static PortalRoomDepthPacket roundTrip(PortalRoomDepthPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PortalRoomDepthPacket.STREAM_CODEC.encode(buf, packet);
        return PortalRoomDepthPacket.STREAM_CODEC.decode(buf);
    }
}
