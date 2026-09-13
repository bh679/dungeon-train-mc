package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.builder.relay.BuilderRelaySubVariant;
import games.brennan.dungeontrain.client.menu.editorscreen.CreatorLoadParent;
import games.brennan.dungeontrain.client.menu.editorscreen.EditorRosterIndex;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What "Load under…" offers, and what picking a row remembers: the category's top-level rows, never
 * a member or the synthetic default room, with <b>User builds (new)</b> on top until it exists.
 */
final class CreatorParentPickerScreenTest {

    private static EditorTypeMenusPacket.Variant v(String cat, String modelId, String name,
                                                    List<EditorTypeMenusPacket.Variant> subs) {
        return new EditorTypeMenusPacket.Variant(name, 1, cat, modelId, name, false, false, subs);
    }

    private static EditorRosterPacket.Entry e(EditorTypeMenusPacket.Variant variant) {
        return new EditorRosterPacket.Entry(variant, 1);
    }

    private static EditorRosterIndex roster(boolean withUserBuilds) {
        EditorTypeMenusPacket.Variant evil = v("PORTALS", "portal_room", "evilhouse", List.of());
        EditorTypeMenusPacket.Variant house = v("PORTALS", "portal_room", "house", List.of(evil));
        EditorTypeMenusPacket.Variant dflt = v("PORTALS", "portal_room", "default", List.of());
        EditorTypeMenusPacket.Variant copper = v("CONTENTS", "copper", "copper", List.of());
        EditorTypeMenusPacket.Variant maze = v("CONTENTS", "maze", "maze", List.of(copper));
        EditorTypeMenusPacket.Variant shop = v("CONTENTS", "shop", "shop", List.of());
        List<EditorRosterPacket.Entry> contents = withUserBuilds
            ? List.of(e(maze), e(shop), e(v("CONTENTS", "user_builds", "user_builds", List.of())
                .withDisplayName("User builds")))
            : List.of(e(maze), e(shop));
        return new EditorRosterIndex(List.of(
            new EditorRosterPacket.Group("contents", "Contents", "", contents),
            new EditorRosterPacket.Group("portals", "Dimensional Carriage", "portal_room",
                List.of(e(dflt), e(house)))),
            "contents", new EditorRosterPacket.TrainSize(9, 7, 7));
    }

    // Untranslated in a unit test: the keys are what the rows carry, and the keys are what is asserted.
    private static final String NEW = "gui.dungeontrain.editor_screen.creator.parent_new";
    private static final String BACK = "gui.dungeontrain.editor_screen.move.back";

    private static List<String> labels(List<CommandMenuEntry> entries) {
        return entries.stream().map(en -> switch (en) {
            case CommandMenuEntry.ClientAction ca -> ca.label();
            case CommandMenuEntry.Back b -> b.label();
            default -> en.getClass().getSimpleName();
        }).toList();
    }

    @BeforeEach
    @AfterEach
    void resetChoice() {
        CreatorLoadParent.reset();
    }

    @Test
    @DisplayName("without a User builds parent the picker offers it as new, then the top-level contents")
    void offersNewDefaultFirst() {
        CreatorParentPickerScreen picker = new CreatorParentPickerScreen(PlotCategory.CONTENTS, null);
        List<CommandMenuEntry> rows = picker.entries(roster(false));
        assertEquals(List.of(NEW, "maze", "shop", BACK), labels(rows),
            "members (copper) are never parents; the default leads because the load will create it");
        assertTrue(((CommandMenuEntry.ClientAction) rows.get(0)).highlighted(),
            "the default is the choice until another is made, and reads as such");
    }

    @Test
    @DisplayName("once User builds exists it is an ordinary row and the (new) offer is gone")
    void existingDefaultIsNotOfferedTwice() {
        CreatorParentPickerScreen picker = new CreatorParentPickerScreen(PlotCategory.CONTENTS, null);
        List<String> rows = labels(picker.entries(roster(true)));
        assertEquals(List.of("maze", "shop", "User builds  ·  user_builds", BACK), rows);
    }

    @Test
    @DisplayName("picking a row remembers it for the category, tells the host, and highlights it after")
    void pickRemembersAndCloses() {
        boolean[] closed = new boolean[1];
        CreatorParentPickerScreen picker = new CreatorParentPickerScreen(PlotCategory.CONTENTS, () -> closed[0] = true);
        CommandMenuEntry maze = picker.entries(roster(false)).get(1);
        assertInstanceOf(CommandMenuEntry.ClientAction.class, maze);
        ((CommandMenuEntry.ClientAction) maze).action().run();
        assertTrue(closed[0], "a picker that stays open after answering looks like it did not hear");
        assertEquals("maze", CreatorLoadParent.parentFor(PlotCategory.CONTENTS));
        assertEquals(BuilderRelaySubVariant.DEFAULT_PARENT_ID, CreatorLoadParent.parentFor(PlotCategory.PORTALS),
            "a contents choice must not move where the next room lands");
        List<CommandMenuEntry> again = picker.entries(roster(false));
        assertFalse(((CommandMenuEntry.ClientAction) again.get(0)).highlighted());
        assertTrue(((CommandMenuEntry.ClientAction) again.get(1)).highlighted());
    }

    @Test
    @DisplayName("rooms: the default room is a fallback, never a parent; members stay hidden")
    void roomsSkipDefaultAndMembers() {
        CreatorParentPickerScreen picker = new CreatorParentPickerScreen(PlotCategory.PORTALS, null);
        assertEquals(List.of(NEW, "house", BACK), labels(picker.entries(roster(false))));
    }

    @Test
    @DisplayName("the parent button's label follows the roster, and falls back to the default's words")
    void buttonLabel() {
        assertEquals("User builds", CreatorLoadParent.labelFor(PlotCategory.CONTENTS, roster(false)),
            "before it exists the default is named by its label, not its id");
        assertEquals("User builds", CreatorLoadParent.labelFor(PlotCategory.CONTENTS, roster(true)));
        CreatorLoadParent.set(PlotCategory.CONTENTS, "MAZE");
        assertEquals("maze", CreatorLoadParent.labelFor(PlotCategory.CONTENTS, roster(false)),
            "ids are case-folded on the way in, and the roster's label is what is shown");
        CreatorLoadParent.set(PlotCategory.CONTENTS, "");
        assertEquals(BuilderRelaySubVariant.DEFAULT_PARENT_ID, CreatorLoadParent.parentFor(PlotCategory.CONTENTS),
            "blank goes back to the default");
        assertEquals("User builds", CreatorLoadParent.labelFor(PlotCategory.CONTENTS, null));
    }

    @Test
    @DisplayName("only the kinds with sub-variants load under a parent")
    void supportedKinds() {
        assertTrue(CreatorLoadParent.supports("contents"));
        assertTrue(CreatorLoadParent.supports("portal_room"));
        assertFalse(CreatorLoadParent.supports("carriage"), "carriages have no group system yet");
        assertFalse(CreatorLoadParent.supports("part"));
        assertFalse(CreatorLoadParent.supports("track"));
        assertFalse(CreatorLoadParent.supports("carriage_group"));
    }
}
