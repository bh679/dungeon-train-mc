package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.ConfirmScreen;
import games.brennan.dungeontrain.client.menu.PortalTestSaveCheckScreen;
import games.brennan.dungeontrain.client.menu.StagePickerScreen;
import games.brennan.dungeontrain.client.menu.UnsavedCheckScreen;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorPlotActionPacket;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the inventory screen's control table: which command each control sends, and — the rule
 * that keeps a click from editing the wrong plot — which controls are offered only while the
 * player stands in the selected template.
 */
final class EditorScreenActionsTest {

    private static EditorTypeMenusPacket.Variant gated(String cat, String modelId, String modelName,
                                                      int weight, List<String> stages) {
        return new EditorTypeMenusPacket.Variant(modelName, weight, 10, 60, 1, cat, modelId, modelName,
            false, false, List.of(), stages);
    }

    private static EditorScreenActions.Ctx ctx(VariantKey sel, EditorTypeMenusPacket.Variant v,
                                               VariantKey standing, PlotCategory stamped) {
        return new EditorScreenActions.Ctx(sel, v, EditorPlotLabelsPacket.NO_WEIGHT, standing, stamped, false);
    }

    private static Map<String, EditorScreenActions.Icon> iconsById(EditorScreenActions.Ctx ctx,
                                                                   List<EditorPlotActionPacket> sent) {
        Map<String, EditorScreenActions.Icon> out = new java.util.LinkedHashMap<>();
        for (EditorScreenActions.Icon i : EditorScreenActions.icons(ctx, sent::add)) out.put(i.id(), i);
        return out;
    }

    private static String command(CommandMenuEntry e) {
        if (e instanceof CommandMenuEntry.Stay s) return s.command();
        if (e instanceof CommandMenuEntry.Run r) return r.command();
        return null;
    }

    // ---- icon row ----

    @Test
    @DisplayName("standing in the selected carriage: every icon is live and position-resolved commands are plain")
    void iconsWhenStanding() {
        VariantKey k = VariantKey.of(PlotCategory.CARRIAGES, "windowed", "windowed");
        EditorScreenActions.Ctx c = ctx(k, gated("CARRIAGES", "windowed", "windowed", 20, List.of()), k, PlotCategory.CARRIAGES);
        Map<String, EditorScreenActions.Icon> icons = iconsById(c, new ArrayList<>());
        assertEquals(List.of("save", "rename", "remove", "undo", "redo", "reset", "clear", "package"),
            new ArrayList<>(icons.keySet()));
        assertEquals("dungeontrain save", command(icons.get("save").entry()));
        assertInstanceOf(CommandMenuEntry.TypeArg.class, icons.get("rename").entry());
        assertEquals("dungeontrain editor undo", command(icons.get("undo").entry()));
        assertEquals("dungeontrain editor redo", command(icons.get("redo").entry()));
        assertEquals("dungeontrain reset", command(icons.get("reset").entry()));
        CommandMenuEntry.DrillIn clear = assertInstanceOf(CommandMenuEntry.DrillIn.class, icons.get("clear").entry());
        assertInstanceOf(ConfirmScreen.class, clear.target());
        CommandMenuEntry.DrillIn remove = assertInstanceOf(CommandMenuEntry.DrillIn.class, icons.get("remove").entry());
        assertInstanceOf(ConfirmScreen.class, remove.target());
        assertTrue(icons.get("package").enabled());
    }

    @Test
    @DisplayName("selecting another plot: undo, redo and rename are disabled; save, reset and clear go by packet")
    void iconsWhenElsewhere() {
        VariantKey sel = VariantKey.of(PlotCategory.CARRIAGES, "pen", "pen");
        VariantKey standing = VariantKey.of(PlotCategory.CARRIAGES, "windowed", "windowed");
        EditorScreenActions.Ctx c = ctx(sel, gated("CARRIAGES", "pen", "pen", 15, List.of()), standing, PlotCategory.CARRIAGES);
        List<EditorPlotActionPacket> sent = new ArrayList<>();
        Map<String, EditorScreenActions.Icon> icons = iconsById(c, sent);
        assertFalse(icons.get("undo").enabled());
        assertFalse(icons.get("redo").enabled());
        assertFalse(icons.get("rename").enabled());
        assertEquals(EditorScreenLang.DISABLED_STAND_HERE, icons.get("undo").disabledKey());
        for (String id : List.of("save", "reset", "clear")) {
            CommandMenuEntry.ClientAction a = assertInstanceOf(CommandMenuEntry.ClientAction.class, icons.get(id).entry(), id);
            a.action().run();
        }
        assertEquals(3, sent.size());
        assertEquals(new EditorPlotActionPacket("carriages", "pen", "pen", EditorPlotActionPacket.Action.SAVE), sent.get(0));
        assertEquals(EditorPlotActionPacket.Action.RESET, sent.get(1).action());
        assertEquals(EditorPlotActionPacket.Action.CLEAR, sent.get(2).action());
        // Remove is addressed by id, so it stays live from anywhere.
        assertTrue(icons.get("remove").enabled());
    }

    @Test
    @DisplayName("parts have no addressed action row, so away from the plot only remove and package remain")
    void iconsForPartsElsewhere() {
        VariantKey sel = VariantKey.of(PlotCategory.PARTS, "walls", "quartz");
        EditorTypeMenusPacket.Variant v = new EditorTypeMenusPacket.Variant("quartz", EditorPlotLabelsPacket.NO_WEIGHT,
            "PARTS", "walls", "quartz", true, false);
        Map<String, EditorScreenActions.Icon> icons = iconsById(ctx(sel, v, null, PlotCategory.CARRIAGES), new ArrayList<>());
        assertFalse(icons.get("save").enabled());
        assertFalse(icons.get("reset").enabled());
        assertFalse(icons.get("clear").enabled());
        CommandMenuEntry.DrillIn remove = assertInstanceOf(CommandMenuEntry.DrillIn.class, icons.get("remove").entry());
        assertEquals("dungeontrain editor part reset walls quartz",
            ((ConfirmScreen) remove.target()).entries().stream()
                .filter(e -> e instanceof CommandMenuEntry.Run).map(e -> ((CommandMenuEntry.Run) e).command()).findFirst().orElse(null));
    }

    @Test
    @DisplayName("a built-in carriage cannot be renamed even when standing in it")
    void builtinRename() {
        VariantKey k = VariantKey.of(PlotCategory.CARRIAGES, "standard", "standard");
        Map<String, EditorScreenActions.Icon> icons = iconsById(
            ctx(k, gated("CARRIAGES", "standard", "standard", 19, List.of()), k, PlotCategory.CARRIAGES), new ArrayList<>());
        assertFalse(icons.get("rename").enabled());
        assertEquals(EditorScreenLang.DISABLED_BUILTIN, icons.get("rename").disabledKey());
    }

    @Test
    @DisplayName("nothing selected: only Package is live")
    void iconsNoSelection() {
        Map<String, EditorScreenActions.Icon> icons = iconsById(ctx(null, null, null, null), new ArrayList<>());
        assertEquals(1, icons.values().stream().filter(EditorScreenActions.Icon::enabled).count());
        assertTrue(icons.get("package").enabled());
    }

    // ---- enter / test ----

    @Test
    @DisplayName("enter within the stamped category runs the enter command; across categories it goes through the unsaved check")
    void enter() {
        VariantKey contents = VariantKey.of(PlotCategory.CONTENTS, "armor", "armor");
        EditorTypeMenusPacket.Variant v = gated("CONTENTS", "armor", "armor", 5, List.of());
        CommandMenuEntry same = EditorScreenActions.enterEntry(ctx(contents, v, null, PlotCategory.CONTENTS));
        assertEquals("dungeontrain editor contents enter armor", ((CommandMenuEntry.Run) same).command());
        CommandMenuEntry cross = EditorScreenActions.enterEntry(ctx(contents, v, null, PlotCategory.CARRIAGES));
        assertInstanceOf(UnsavedCheckScreen.class, ((CommandMenuEntry.DrillIn) cross).target());
        // Parts stamp with carriages, so from a carriage plot a part is a same-category enter.
        VariantKey part = VariantKey.of(PlotCategory.PARTS, "floor", "oak");
        EditorTypeMenusPacket.Variant pv = new EditorTypeMenusPacket.Variant("oak", -1, "PARTS", "floor", "oak", false, false);
        assertInstanceOf(CommandMenuEntry.Run.class, EditorScreenActions.enterEntry(ctx(part, pv, null, PlotCategory.CARRIAGES)));
        assertNull(EditorScreenActions.enterEntry(ctx(null, null, null, null)));
    }

    @Test
    @DisplayName("Test the Carriage is offered only from inside the selected dimension room")
    void testCarriage() {
        VariantKey room = VariantKey.of(PlotCategory.PORTALS, "portal_room", "house");
        EditorTypeMenusPacket.Variant v = gated("PORTALS", "portal_room", "house", 1, List.of());
        CommandMenuEntry inside = EditorScreenActions.testEntry(ctx(room, v, room, PlotCategory.PORTALS));
        assertInstanceOf(PortalTestSaveCheckScreen.class, ((CommandMenuEntry.DrillIn) inside).target());
        assertNull(EditorScreenActions.testEntry(ctx(room, v, VariantKey.of(PlotCategory.PORTALS, "portal_room", "beam"), PlotCategory.PORTALS)));
        VariantKey carriage = VariantKey.of(PlotCategory.CARRIAGES, "pen", "pen");
        assertNull(EditorScreenActions.testEntry(ctx(carriage, gated("CARRIAGES", "pen", "pen", 1, List.of()), carriage, PlotCategory.CARRIAGES)));
    }

    // ---- settings rows ----

    private static final Function<EditorScreenActions.Ctx, List<CommandMenuEntry>> ROWS =
        c -> EditorScreenActions.settingRows(c, () -> List.of(new CommandMenuEntry.Label("ROOM")), () -> "");

    @Test
    @DisplayName("a top-level carriage gets weight, min/max, phases, stage and the contents allow-list")
    void carriageRows() {
        VariantKey k = VariantKey.of(PlotCategory.CARRIAGES, "pen", "pen");
        List<CommandMenuEntry> rows = ROWS.apply(ctx(k, gated("CARRIAGES", "pen", "pen", 15, List.of()), null, PlotCategory.CARRIAGES));
        List<String> labels = rows.stream().map(CommandMenuEntry::label).toList();
        assertEquals(6, rows.size(), labels.toString());
        CommandMenuEntry.Triple weight = assertInstanceOf(CommandMenuEntry.Triple.class, rows.get(0));
        assertEquals("dungeontrain editor weight pen dec", ((CommandMenuEntry.Stay) weight.leftEntry()).command());
        CommandMenuEntry.Triple min = assertInstanceOf(CommandMenuEntry.Triple.class, rows.get(1));
        assertEquals("dungeontrain editor minlevel pen inc", ((CommandMenuEntry.Stay) min.rightEntry()).command());
        assertTrue(min.middleEntry().label().contains("10"));
        assertTrue(rows.get(2).label().contains("60"));
        CommandMenuEntry.DrillIn phases = assertInstanceOf(CommandMenuEntry.DrillIn.class, rows.get(3));
        List<CommandMenuEntry> toggles = phases.target().entries();
        CommandMenuEntry.Toggle overworld = assertInstanceOf(CommandMenuEntry.Toggle.class, toggles.get(0));
        assertTrue(overworld.state());
        assertEquals("dungeontrain editor phase pen overworld off", overworld.cmdToTurnOff());
        assertInstanceOf(CommandMenuEntry.Back.class, toggles.get(toggles.size() - 1));
        assertInstanceOf(StagePickerScreen.class, ((CommandMenuEntry.DrillIn) rows.get(4)).target());
        assertNotNull(rows.get(5));
    }

    @Test
    @DisplayName("a stage-linked template collapses the gate into one Stage chip")
    void stageLinkedRows() {
        VariantKey k = VariantKey.of(PlotCategory.CONTENTS, "fire", "fire");
        List<CommandMenuEntry> rows = ROWS.apply(ctx(k, gated("CONTENTS", "fire", "fire", 2, List.of("nether")), null, PlotCategory.CONTENTS));
        assertEquals(2, rows.size(), rows.toString());
        // The chip's text is a translated format string, unresolved in a unit test; the target is the pin.
        assertInstanceOf(StagePickerScreen.class, ((CommandMenuEntry.DrillIn) rows.get(1)).target());
    }

    @Test
    @DisplayName("a contents sub-variant gets only the group weight verb")
    void subVariantRows() {
        VariantKey member = new VariantKey(PlotCategory.CONTENTS, "armor5", "armor5", "armor");
        EditorTypeMenusPacket.Variant v = new EditorTypeMenusPacket.Variant("armor5", 6, "CONTENTS", "armor5", "armor5", false, false);
        List<CommandMenuEntry> rows = ROWS.apply(ctx(member, v, null, PlotCategory.CONTENTS));
        assertEquals(1, rows.size());
        CommandMenuEntry.Triple weight = assertInstanceOf(CommandMenuEntry.Triple.class, rows.get(0));
        assertEquals("dungeontrain editor contents group set-weight armor armor5 inc",
            ((CommandMenuEntry.Stay) weight.rightEntry()).command());
        VariantKey roomMember = new VariantKey(PlotCategory.PORTALS, "portal_room", "evilhouse", "house");
        EditorTypeMenusPacket.Variant rv = new EditorTypeMenusPacket.Variant("evilhouse", 1, "PORTALS", "portal_room", "evilhouse", false, false);
        CommandMenuEntry.Triple rw = assertInstanceOf(CommandMenuEntry.Triple.class,
            ROWS.apply(ctx(roomMember, rv, null, PlotCategory.PORTALS)).get(0));
        assertEquals("dungeontrain editor portals group set-weight house evilhouse dec",
            ((CommandMenuEntry.Stay) rw.leftEntry()).command());
    }

    @Test
    @DisplayName("room geometry rows appear only while standing in the selected dimension room")
    void roomRowsOnlyWhenStanding() {
        VariantKey room = VariantKey.of(PlotCategory.PORTALS, "portal_room", "house");
        EditorTypeMenusPacket.Variant v = gated("PORTALS", "portal_room", "house", 1, List.of());
        List<CommandMenuEntry> inside = ROWS.apply(ctx(room, v, room, PlotCategory.PORTALS));
        assertTrue(inside.stream().anyMatch(e -> "ROOM".equals(e.label())));
        List<CommandMenuEntry> away = ROWS.apply(ctx(room, v, null, PlotCategory.PORTALS));
        assertFalse(away.stream().anyMatch(e -> "ROOM".equals(e.label())));
        // A part has no weight pool and no gate: nothing to show.
        VariantKey part = VariantKey.of(PlotCategory.PARTS, "floor", "oak");
        EditorTypeMenusPacket.Variant pv = new EditorTypeMenusPacket.Variant("oak", EditorPlotLabelsPacket.NO_WEIGHT, "PARTS", "floor", "oak", false, false);
        assertTrue(ROWS.apply(ctx(part, pv, part, PlotCategory.CARRIAGES)).isEmpty());
    }

    // ---- new ----

    @Test
    @DisplayName("the + tile picks the right source flow per strip")
    void newEntries() {
        assertInstanceOf(CommandMenuEntry.DrillIn.class,
            EditorScreenActions.newEntry(PlotCategory.CARRIAGES, "", "standard", null));
        CommandMenuEntry tracks = EditorScreenActions.newEntry(PlotCategory.TRACKS, "pillar_top", "default", null);
        assertEquals("dungeontrain editor tracks new pillar_top", ((CommandMenuEntry.TypeArg) tracks).commandPrefix());
        CommandMenuEntry rooms = EditorScreenActions.newEntry(PlotCategory.PORTALS, "portal_room", "default", null);
        assertEquals("dungeontrain editor portals new portal_room", ((CommandMenuEntry.TypeArg) rooms).commandPrefix());
        assertInstanceOf(CommandMenuEntry.DrillIn.class,
            EditorScreenActions.newEntry(PlotCategory.PARTS, "walls", "quartz", null));
        assertNull(EditorScreenActions.newEntry(PlotCategory.ARCHITECTURE, "", "", null));
        assertNotNull(EditorScreenActions.newSubVariantEntry(VariantKey.of(PlotCategory.CONTENTS, "armor", "armor"), null));
        assertNull(EditorScreenActions.newSubVariantEntry(VariantKey.of(PlotCategory.CARRIAGES, "pen", "pen"), null));
    }
}
