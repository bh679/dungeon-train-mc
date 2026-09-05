package games.brennan.dungeontrain.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wire-format round trip for {@link EditorRosterPacket}. */
final class EditorRosterPacketTest {

    private static EditorRosterPacket roundTrip(EditorRosterPacket original) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        original.encode(buf);
        EditorRosterPacket decoded = EditorRosterPacket.decode(buf);
        assertFalse(buf.isReadable(), "decode must consume exactly what encode wrote");
        return decoded;
    }

    private static EditorRosterPacket sample() {
        EditorTypeMenusPacket.Variant member = new EditorTypeMenusPacket.Variant(
            "armor2", 5, 0, -1, 1, "CONTENTS", "armor2", "armor2", true, false, List.of(), List.of("desert"));
        EditorTypeMenusPacket.Variant parent = new EditorTypeMenusPacket.Variant(
            "armor", 5, 0, -1, 1, "CONTENTS", "armor", "armor", false, false, List.of(member), List.of());
        EditorTypeMenusPacket.Variant carriage = new EditorTypeMenusPacket.Variant(
            "standard", 19, "CARRIAGES", "standard", "standard", false, false);
        return new EditorRosterPacket(List.of(
            new EditorRosterPacket.Group("carriages", "Carriages", "",
                List.of(new EditorRosterPacket.Entry(carriage, EditorPlotLabelsPacket.NO_WEIGHT))),
            new EditorRosterPacket.Group("contents", "Contents", "",
                List.of(new EditorRosterPacket.Entry(parent, 3)))),
            "contents", new EditorRosterPacket.TrainSize(9, 7, 7));
    }

    @Test
    @DisplayName("groups, entries, nested sub-variants and self weights survive the round trip")
    void roundTrip() {
        EditorRosterPacket decoded = roundTrip(sample());
        assertEquals("contents", decoded.stampedCategoryId());
        assertEquals(2, decoded.groups().size());
        EditorRosterPacket.Group contents = decoded.groups().get(1);
        assertEquals("Contents", contents.typeName());
        assertEquals(3, contents.entries().get(0).selfWeight());
        EditorTypeMenusPacket.Variant parent = contents.entries().get(0).variant();
        assertEquals("armor", parent.name());
        assertEquals(1, parent.subVariants().size());
        assertEquals(List.of("desert"), parent.subVariants().get(0).stageIds());
        assertTrue(parent.subVariants().get(0).isUser());
        assertEquals(EditorPlotLabelsPacket.NO_WEIGHT, decoded.groups().get(0).entries().get(0).selfWeight());
        assertEquals(new EditorRosterPacket.TrainSize(9, 7, 7), decoded.trainSize());
        assertTrue(decoded.trainSize().isKnown());
    }

    @Test
    @DisplayName("an empty roster is buffer-symmetric and normalises a null stamped category")
    void empty() {
        EditorRosterPacket decoded = roundTrip(new EditorRosterPacket(List.of(), null, null));
        assertTrue(decoded.groups().isEmpty());
        assertEquals("", decoded.stampedCategoryId());
        assertFalse(decoded.trainSize().isKnown(), "an unknown footprint leaves the sheet measuring");
    }
}
