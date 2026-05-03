package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-format round-trip for {@link EditorStatusPacket}. The {@code modelId}
 * and {@code modelName} fields matter for the menu: track-side models send
 * different strings in {@code model} (HUD path), {@code modelId} (kind tag),
 * and {@code modelName} (bare variant name) — the encoder/decoder must
 * preserve all three independently.
 */
final class EditorStatusPacketTest {

    @Test
    @DisplayName("round-trip preserves all fields")
    void roundTrip_carriages() {
        EditorStatusPacket original = new EditorStatusPacket(
            "Carriages", "standard", "standard", "standard", true, 50, true, Collections.emptySet());
        EditorStatusPacket decoded = roundTrip(original);
        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("round-trip preserves the excludedContents set")
    void roundTrip_excludedContents() {
        EditorStatusPacket original = new EditorStatusPacket(
            "Carriages", "standard", "standard", "standard", false, 5, true,
            Set.of("default", "lava_pool"));
        EditorStatusPacket decoded = roundTrip(original);
        assertEquals(2, decoded.excludedContents().size());
        assertTrue(decoded.excludedContents().contains("default"));
        assertTrue(decoded.excludedContents().contains("lava_pool"));
    }

    @Test
    @DisplayName("round-trip keeps model, modelId, and modelName distinct for track-side models")
    void roundTrip_tracks_pathStringAndKindAndNameSurviveSeparately() {
        EditorStatusPacket original = new EditorStatusPacket(
            "Tracks", "track / track2", "track", "track2", false, 5, true, Collections.emptySet());
        EditorStatusPacket decoded = roundTrip(original);
        assertEquals("Tracks", decoded.category());
        assertEquals("track / track2", decoded.model());
        assertEquals("track", decoded.modelId());
        assertEquals("track2", decoded.modelName());
        assertEquals(false, decoded.devmode());
        assertEquals(5, decoded.weight());
        assertEquals(true, decoded.partMenuEnabled());
    }

    @Test
    @DisplayName("round-trip handles the empty clear packet")
    void roundTrip_empty() {
        EditorStatusPacket decoded = roundTrip(EditorStatusPacket.empty());
        assertEquals("", decoded.category());
        assertEquals("", decoded.model());
        assertEquals("", decoded.modelId());
        assertEquals("", decoded.modelName());
        assertEquals(false, decoded.devmode());
        assertEquals(EditorStatusPacket.NO_WEIGHT, decoded.weight());
        assertEquals(true, decoded.partMenuEnabled());
    }

    @Test
    @DisplayName("round-trip handles pillar and tunnel modelIds + modelNames")
    void roundTrip_pillarsAndTunnels() {
        EditorStatusPacket pillar = roundTrip(new EditorStatusPacket(
            "Tracks", "pillar / bottom / stone", "pillar_bottom", "stone", false, 3, true, Collections.emptySet()));
        assertEquals("pillar_bottom", pillar.modelId());
        assertEquals("stone", pillar.modelName());
        assertEquals("pillar / bottom / stone", pillar.model());
        assertEquals(3, pillar.weight());

        EditorStatusPacket tunnel = roundTrip(new EditorStatusPacket(
            "Tracks", "tunnel / section / default", "tunnel_section", "default", true, 1, false, Collections.emptySet()));
        assertEquals("tunnel_section", tunnel.modelId());
        assertEquals("default", tunnel.modelName());
        assertEquals("tunnel / section / default", tunnel.model());
        assertEquals(1, tunnel.weight());
        assertEquals(false, tunnel.partMenuEnabled());
    }

    @Test
    @DisplayName("round-trip handles contents (modelId == modelName)")
    void roundTrip_contents() {
        EditorStatusPacket original = new EditorStatusPacket(
            "Contents", "default", "default", "default", false, 7, true, Collections.emptySet());
        EditorStatusPacket decoded = roundTrip(original);
        assertEquals(original, decoded);
        assertEquals("default", decoded.modelName());
    }

    private static EditorStatusPacket roundTrip(EditorStatusPacket original) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(buf);
        return EditorStatusPacket.decode(buf);
    }
}
