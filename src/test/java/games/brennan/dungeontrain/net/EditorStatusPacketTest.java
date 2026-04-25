package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wire-format round-trip for {@link EditorStatusPacket}. The {@code modelId}
 * field is the bit that matters for the menu: track-side models send a
 * different string in {@code model} (HUD path) and {@code modelId} (command
 * token) — the encoder/decoder must preserve both independently.
 */
final class EditorStatusPacketTest {

    @Test
    @DisplayName("round-trip preserves all fields")
    void roundTrip_carriages() {
        EditorStatusPacket original = new EditorStatusPacket(
            "Carriages", "standard", "standard", true, 50);
        EditorStatusPacket decoded = roundTrip(original);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("round-trip keeps model and modelId distinct for track-side models")
    void roundTrip_tracks_pathStringAndKindSurviveSeparately() {
        EditorStatusPacket original = new EditorStatusPacket(
            "Tracks", "track / track2", "track", false, EditorStatusPacket.NO_WEIGHT);
        EditorStatusPacket decoded = roundTrip(original);
        assertEquals("Tracks", decoded.category());
        assertEquals("track / track2", decoded.model());
        assertEquals("track", decoded.modelId());
        assertEquals(false, decoded.devmode());
        assertEquals(EditorStatusPacket.NO_WEIGHT, decoded.weight());
    }

    @Test
    @DisplayName("round-trip handles the empty clear packet")
    void roundTrip_empty() {
        EditorStatusPacket decoded = roundTrip(EditorStatusPacket.empty());
        assertEquals("", decoded.category());
        assertEquals("", decoded.model());
        assertEquals("", decoded.modelId());
        assertEquals(false, decoded.devmode());
        assertEquals(EditorStatusPacket.NO_WEIGHT, decoded.weight());
    }

    @Test
    @DisplayName("round-trip handles pillar and tunnel modelIds")
    void roundTrip_pillarsAndTunnels() {
        EditorStatusPacket pillar = roundTrip(new EditorStatusPacket(
            "Tracks", "pillar / bottom / stone", "pillar_bottom", false, EditorStatusPacket.NO_WEIGHT));
        assertEquals("pillar_bottom", pillar.modelId());
        assertEquals("pillar / bottom / stone", pillar.model());

        EditorStatusPacket tunnel = roundTrip(new EditorStatusPacket(
            "Tracks", "tunnel / section / default", "tunnel_section", true, EditorStatusPacket.NO_WEIGHT));
        assertEquals("tunnel_section", tunnel.modelId());
        assertEquals("tunnel / section / default", tunnel.model());
    }

    private static EditorStatusPacket roundTrip(EditorStatusPacket original) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(buf);
        return EditorStatusPacket.decode(buf);
    }
}
