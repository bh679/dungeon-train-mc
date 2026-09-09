package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.client.menu.editorscreen.EditorRosterIndex;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What "Move to…" offers, read off a roster: the same category's top-level rows minus the template
 * itself, minus its current parent, minus the synthetic default room — and never a member, which
 * the roster only ever lists under its parent.
 */
final class GroupParentPickerScreenTest {

    private static EditorTypeMenusPacket.Variant v(String cat, String modelId, String name,
                                                    List<EditorTypeMenusPacket.Variant> subs) {
        return new EditorTypeMenusPacket.Variant(name, 1, cat, modelId, name, false, false, subs);
    }

    private static EditorRosterPacket.Entry e(EditorTypeMenusPacket.Variant variant) {
        return new EditorRosterPacket.Entry(variant, 1);
    }

    private static EditorRosterIndex sample() {
        EditorTypeMenusPacket.Variant evil = v("PORTALS", "portal_room", "evilhouse", List.of());
        EditorTypeMenusPacket.Variant house = v("PORTALS", "portal_room", "house", List.of(evil));
        EditorTypeMenusPacket.Variant book = v("PORTALS", "portal_room", "book", List.of())
            .withDisplayName("Tome");
        EditorTypeMenusPacket.Variant dflt = v("PORTALS", "portal_room", "default", List.of());
        EditorTypeMenusPacket.Variant copper = v("CONTENTS", "copper", "copper", List.of());
        EditorTypeMenusPacket.Variant maze = v("CONTENTS", "maze", "maze", List.of(copper));
        EditorTypeMenusPacket.Variant shop = v("CONTENTS", "shop", "shop", List.of());
        return new EditorRosterIndex(List.of(
            new EditorRosterPacket.Group("contents", "Contents", "", List.of(e(maze), e(shop))),
            new EditorRosterPacket.Group("portals", "Dimensional Carriage", "portal_room",
                List.of(e(dflt), e(house), e(book)))),
            "portals", new EditorRosterPacket.TrainSize(9, 7, 7));
    }

    private static List<String> ids(List<EditorTypeMenusPacket.Variant> vs) {
        return vs.stream().map(EditorTypeMenusPacket.Variant::modelName).toList();
    }

    @Test
    @DisplayName("a room under a parent: the other top-level rooms, not its parent, not default, not members")
    void roomCandidates() {
        GroupParentPickerScreen picker = new GroupParentPickerScreen(PlotCategory.PORTALS, "evilhouse", "house");
        assertEquals(List.of("book"), ids(picker.candidateParents(sample())));
        assertEquals("dungeontrain editor portals group move evilhouse book", picker.moveCommand("book"));
        assertEquals("dungeontrain editor portals group remove house evilhouse", picker.promoteCommand());
    }

    @Test
    @DisplayName("a top-level room: every other top-level room; a pick demotes it")
    void topLevelRoomCandidates() {
        GroupParentPickerScreen picker = new GroupParentPickerScreen(PlotCategory.PORTALS, "book", "");
        assertEquals(List.of("house"), ids(picker.candidateParents(sample())));
        assertEquals("dungeontrain editor portals group add house book", picker.moveCommand("house"));
    }

    @Test
    @DisplayName("contents use the contents group verbs and never see the portal rows")
    void contentsCandidates() {
        GroupParentPickerScreen picker = new GroupParentPickerScreen(PlotCategory.CONTENTS, "copper", "maze");
        assertEquals(List.of("shop"), ids(picker.candidateParents(sample())));
        assertEquals("dungeontrain editor contents group move copper shop", picker.moveCommand("shop"));
        assertEquals("dungeontrain editor contents group remove maze copper", picker.promoteCommand());
    }

    @Test
    @DisplayName("an empty roster offers nothing rather than failing")
    void emptyRoster() {
        GroupParentPickerScreen picker = new GroupParentPickerScreen(PlotCategory.CONTENTS, "copper", "");
        assertEquals(List.of(), picker.candidateParents(EditorRosterIndex.EMPTY));
        assertEquals(List.of(), picker.candidateParents(null));
    }
}
