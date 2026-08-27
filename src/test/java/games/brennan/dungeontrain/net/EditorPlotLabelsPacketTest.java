package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire round-trip for {@link EditorPlotLabelsPacket}, focused on the
 * {@code category} column.
 *
 * <p>That column carries the uppercase editor-category vocabulary plus the
 * pseudo-category {@code "PARTS"}, which is not an {@code EditorCategory}
 * constant, and {@code ""} for rows with no category at all. All three shapes
 * have to survive the buffer unchanged — a parse that tightens on decode would
 * strand the last two.</p>
 */
final class EditorPlotLabelsPacketTest {

    private static EditorPlotLabelsPacket roundTrip(EditorPlotLabelsPacket original) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(buf);
        EditorPlotLabelsPacket decoded = EditorPlotLabelsPacket.decode(buf);
        assertFalse(buf.isReadable(), "decode must consume exactly what encode wrote");
        return decoded;
    }

    private static EditorPlotLabelsPacket.Entry entry(String category, String modelId, String modelName) {
        return new EditorPlotLabelsPacket.Entry(
            new BlockPos(4, 250, -12), modelName, EditorPlotLabelsPacket.NO_WEIGHT,
            category, modelId, modelName, true, false, false);
    }

    @Test
    @DisplayName("round-trip preserves every category value including PARTS and the empty one")
    void roundTrip_preservesCategoryVocabulary() {
        EditorPlotLabelsPacket original = new EditorPlotLabelsPacket(List.of(
            entry("CARRIAGES", "std", "std"),
            entry("CONTENTS", "crates", "crates"),
            entry("TRACKS", "pillar_top", "fancy"),
            entry("PORTALS", "portal_room", "library"),
            entry("PARTS", "floor", "checker"),
            entry("", "", "")));

        EditorPlotLabelsPacket decoded = roundTrip(original);

        assertEquals(6, decoded.entries().size());
        assertEquals("CARRIAGES", decoded.entries().get(0).category());
        assertEquals("PARTS", decoded.entries().get(4).category());
        assertEquals("floor", decoded.entries().get(4).modelId());
        assertEquals("checker", decoded.entries().get(4).modelName());
        assertEquals("", decoded.entries().get(5).category());
    }

    @Test
    @DisplayName("round-trip preserves a portal room's authored box and mode")
    void roundTrip_preservesRoomBox() {
        EditorPlotLabelsPacket.Entry room = new EditorPlotLabelsPacket.Entry(
            new BlockPos(0, 250, 0), "library", 7,
            "PORTALS", "portal_room", "library", true, true, false,
            9, 11, 5, "endless_open");

        EditorPlotLabelsPacket.Entry decoded = roundTrip(
            new EditorPlotLabelsPacket(List.of(room))).entries().get(0);

        assertEquals("PORTALS", decoded.category());
        assertEquals(9, decoded.roomLength());
        assertEquals(11, decoded.roomWidth());
        assertEquals(5, decoded.roomHeight());
        assertEquals("endless_open", decoded.roomMode());
        assertTrue(decoded.isUser());
    }

    @Test
    @DisplayName("an empty snapshot round-trips to the empty singleton")
    void roundTrip_empty() {
        assertTrue(roundTrip(EditorPlotLabelsPacket.empty()).entries().isEmpty());
    }
}
