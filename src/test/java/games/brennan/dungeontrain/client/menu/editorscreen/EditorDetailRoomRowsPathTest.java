package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole client path from a roster packet to the rows under the 3D preview, for a room the
 * author is not standing in — the path the screen runs, minus the pixels.
 */
class EditorDetailRoomRowsPathTest {

    @Test
    @DisplayName("a decoded roster row selected in the browser yields the room's rows, Fog and Walls included")
    void packetToRows() {
        // The row exactly as EditorTypeMenus.trackKindRows builds it: category is the enum NAME.
        EditorTypeMenusPacket.Variant room = new EditorTypeMenusPacket.Variant(
            "labrynth", 3, 0, -1, 1, "PORTALS", "portal_room", "labrynth", false, false, List.of(), "");
        EditorRosterPacket sent = new EditorRosterPacket(List.of(
            new EditorRosterPacket.Group("portals", "Dimensional Carriage", "portal_room",
                List.of(new EditorRosterPacket.Entry(room, 1).withRoom("endless_repetition/dynamic", 11, 13, 7)))),
            "portals");
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        sent.encode(buf);
        EditorRosterPacket got = EditorRosterPacket.decode(buf);

        EditorRosterIndex index = new EditorRosterIndex(got.groups(), got.stampedCategoryId(), got.trainSize());
        // What a click on the browser tile selects.
        VariantKey key = VariantKey.of(room, "");
        EditorRosterIndex.Tile tile = index.find(key);
        assertTrue(tile.extras().hasRoom(), "the tile carries the room's tag");

        EditorScreenActions.Ctx ctx = new EditorScreenActions.Ctx(tile.key(), tile.variant(), tile.selfWeight(),
            null, PlotCategory.PORTALS, false, tile.extras());
        List<CommandMenuEntry> rows = EditorScreenActions.roomRows(ctx);
        List<String> labels = rows.stream().map(CommandMenuEntry::label).toList();
        assertTrue(labels.stream().anyMatch(l -> l.startsWith("Walls")), labels.toString());
        assertTrue(labels.stream().anyMatch(l -> l.startsWith("Copies")), labels.toString());
        assertTrue(labels.stream().anyMatch(l -> l.startsWith("Fog: Auto (On)")), labels.toString());

        List<CommandMenuEntry> settings = EditorScreenActions.settingRows(ctx, () -> rows,
            () -> EditorScreenActions.roomModeOf(ctx, () -> ""));
        assertTrue(settings.stream().anyMatch(r -> r.label().startsWith("Fog")), settings.toString());
        assertEquals("dungeontrain editor portals room labrynth fog next",
            ((CommandMenuEntry.Stay) settings.stream().filter(r -> r.label().startsWith("Fog")).findFirst().orElseThrow()).command());
    }
}
