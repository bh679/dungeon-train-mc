package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.portal.PortalRoomSky;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format round-trip for {@link PortalRoomSkyPacket}, and the two named constructors that decide
 * whether the author's Plot Lighting switch reaches a box.
 *
 * <p>The {@code editor} flag is the load-bearing one: a live room's lift is not optional, so an
 * encoder that dropped the flag — or a factory that set it the wrong way round — would quietly hand
 * every player's dimensional carriage to a client-side off switch meant for the editor.</p>
 */
final class PortalRoomSkyPacketTest {

    @Test
    @DisplayName("round-trip preserves bounds, sky and the editor flag")
    void roundTrip_editorRegion() {
        PortalRoomSkyPacket original = PortalRoomSkyPacket.onPlot(
            -40, 230, 512, -9, 244, 543, PortalRoomSky.NETHER.ordinal());
        PortalRoomSkyPacket decoded = roundTrip(original);
        assertEquals(original, decoded);
        assertTrue(decoded.editor());
        assertEquals(PortalRoomSky.NETHER, decoded.skyKind());
    }

    @Test
    @DisplayName("round-trip preserves a world region as not-editor")
    void roundTrip_worldRegion() {
        PortalRoomSkyPacket original = PortalRoomSkyPacket.inWorld(
            0, -60, 0, 31, -46, 31, PortalRoomSky.DAY.ordinal());
        PortalRoomSkyPacket decoded = roundTrip(original);
        assertEquals(original, decoded);
        assertFalse(decoded.editor());
        assertEquals(PortalRoomSky.DAY, decoded.skyKind());
    }

    @Test
    @DisplayName("the clear packet is NONE and is not an editor region")
    void none_isNotEditor() {
        PortalRoomSkyPacket none = roundTrip(PortalRoomSkyPacket.none());
        assertEquals(PortalRoomSky.NONE, none.skyKind());
        assertFalse(none.editor());
    }

    private static PortalRoomSkyPacket roundTrip(PortalRoomSkyPacket original) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PortalRoomSkyPacket.STREAM_CODEC.encode(buf, original);
        return PortalRoomSkyPacket.STREAM_CODEC.decode(buf);
    }
}
