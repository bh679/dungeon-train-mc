package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.builder.BuilderProfileState;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.ConfirmScreen;
import games.brennan.dungeontrain.client.menu.GroupParentPickerScreen;
import games.brennan.dungeontrain.client.menu.ParentRemoveConfirmScreen;
import games.brennan.dungeontrain.client.menu.PortalTestSaveCheckScreen;
import games.brennan.dungeontrain.client.menu.StagePickerScreen;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import games.brennan.dungeontrain.net.EditorPlotActionPacket;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorStatusPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.net.BuilderSavePacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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
                                                                   List<CustomPacketPayload> sent) {
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
        assertEquals(List.of("save", "rename", "move", "remove", "undo", "redo", "reset", "clear", "submit"),
            new ArrayList<>(icons.keySet()));
        assertEquals("dungeontrain save", command(icons.get("save").entry()));
        assertInstanceOf(CommandMenuEntry.TypeArg.class, icons.get("rename").entry());
        // Carriages have no sub-variant groups, so there is nowhere to move one.
        assertFalse(icons.get("move").enabled());
        assertEquals(EditorScreenLang.DISABLED_NO_GROUPS, icons.get("move").disabledKey());
        assertEquals("dungeontrain reset", command(icons.get("reset").entry()));
        // Undo and Redo follow the server's history stack, which is empty here — see historyIcon.
        assertFalse(icons.get("undo").enabled());
        assertFalse(icons.get("redo").enabled());
        CommandMenuEntry.DrillIn clear = assertInstanceOf(CommandMenuEntry.DrillIn.class, icons.get("clear").entry());
        assertInstanceOf(ConfirmScreen.class, clear.target());
        CommandMenuEntry.DrillIn remove = assertInstanceOf(CommandMenuEntry.DrillIn.class, icons.get("remove").entry());
        assertInstanceOf(ConfirmScreen.class, remove.target());
        // Submit needs a relay row, and this ctx has none — see submitIcon.
        assertFalse(icons.get("submit").enabled());
        assertEquals(EditorScreenLang.DISABLED_NOT_UPLOADED, icons.get("submit").disabledKey());
    }

    @Test
    @DisplayName("selecting another plot: save, reset and clear go by packet; rename still acts on the selection")
    void iconsWhenElsewhere() {
        VariantKey sel = VariantKey.of(PlotCategory.CARRIAGES, "pen", "pen");
        VariantKey standing = VariantKey.of(PlotCategory.CARRIAGES, "windowed", "windowed");
        EditorScreenActions.Ctx c = ctx(sel, gated("CARRIAGES", "pen", "pen", 15, List.of()), standing, PlotCategory.CARRIAGES);
        List<CustomPacketPayload> sent = new ArrayList<>();
        Map<String, EditorScreenActions.Icon> icons = iconsById(c, sent);
        assertFalse(icons.get("undo").enabled());
        assertFalse(icons.get("redo").enabled());
        assertEquals(EditorScreenLang.UNDO_NOTHING, icons.get("undo").disabledKey());
        // Rename is addressed by id and is a display label, so it acts on the selection from anywhere
        // and the typed field starts on what the row currently shows.
        CommandMenuEntry.TypeArg rename = assertInstanceOf(CommandMenuEntry.TypeArg.class, icons.get("rename").entry());
        assertEquals("dungeontrain editor label pen", rename.commandPrefix());
        assertEquals("pen", rename.initialBuffer());
        for (String id : List.of("save", "reset", "clear")) {
            CommandMenuEntry.ClientAction a = assertInstanceOf(CommandMenuEntry.ClientAction.class, icons.get(id).entry(), id);
            a.action().run();
        }
        assertEquals(3, sent.size());
        assertEquals(new EditorPlotActionPacket("carriages", "pen", "pen", EditorPlotActionPacket.Action.SAVE), sent.get(0));
        assertEquals(EditorPlotActionPacket.Action.RESET, ((EditorPlotActionPacket) sent.get(1)).action());
        assertEquals(EditorPlotActionPacket.Action.CLEAR, ((EditorPlotActionPacket) sent.get(2)).action());
        // Remove is addressed by id, so it stays live from anywhere.
        assertTrue(icons.get("remove").enabled());
    }

    @Test
    @DisplayName("parts have no addressed action row, so away from the plot only remove remains")
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
    @DisplayName("a built-in carriage can be labelled — the rename is a label, not a file move")
    void builtinRename() {
        VariantKey k = VariantKey.of(PlotCategory.CARRIAGES, "standard", "standard");
        EditorTypeMenusPacket.Variant labelled = gated("CARRIAGES", "standard", "standard", 19, List.of())
            .withDisplayName("The Standard");
        Map<String, EditorScreenActions.Icon> icons = iconsById(
            ctx(k, labelled, k, PlotCategory.CARRIAGES), new ArrayList<>());
        CommandMenuEntry.TypeArg rename = assertInstanceOf(CommandMenuEntry.TypeArg.class, icons.get("rename").entry());
        assertEquals("dungeontrain editor label standard", rename.commandPrefix());
        assertEquals("The Standard", rename.initialBuffer(), "the field starts on the current label");
    }

    @Test
    @DisplayName("contents and dimensional carriages get Move to…, keyed by the id the group commands use")
    void moveEntryForGroupedKinds() {
        VariantKey contents = VariantKey.of(PlotCategory.CONTENTS, "copper", "copper");
        Map<String, EditorScreenActions.Icon> icons = iconsById(
            ctx(contents, gated("CONTENTS", "copper", "copper", 3, List.of()), null, PlotCategory.CARRIAGES), new ArrayList<>());
        CommandMenuEntry.DrillIn move = assertInstanceOf(CommandMenuEntry.DrillIn.class, icons.get("move").entry());
        GroupParentPickerScreen picker = assertInstanceOf(GroupParentPickerScreen.class, move.target());
        // Top-level today: a pick demotes it under the chosen parent.
        assertEquals("dungeontrain editor contents group add maze copper", picker.moveCommand("maze"));

        VariantKey room = new VariantKey(PlotCategory.PORTALS, "portal_room", "evilhouse", "house");
        icons = iconsById(ctx(room, gated("PORTALS", "portal_room", "evilhouse", 1, List.of()), null, PlotCategory.CARRIAGES),
            new ArrayList<>());
        picker = assertInstanceOf(GroupParentPickerScreen.class,
            assertInstanceOf(CommandMenuEntry.DrillIn.class, icons.get("move").entry()).target());
        assertEquals("dungeontrain editor portals group move evilhouse book", picker.moveCommand("book"));
        assertEquals("dungeontrain editor portals group remove house evilhouse", picker.promoteCommand());
    }

    @Test
    @DisplayName("nothing selected: nothing in the row is live")
    void iconsNoSelection() {
        Map<String, EditorScreenActions.Icon> icons = iconsById(ctx(null, null, null, null), new ArrayList<>());
        assertEquals(0, icons.values().stream().filter(EditorScreenActions.Icon::enabled).count());
    }

    @Test
    @DisplayName("Submit reads where the build stands from this player's own listing, not the roster")
    void submitIconFollowsTheRelayRow() {
        BuilderProfileState.clearCache();
        assertFalse(EditorScreenActions.submitIcon(0).enabled(), "no relay row, nothing to submit");
        assertFalse(EditorScreenActions.submitIcon(41).enabled(), "no listing yet is the same answer");

        BuilderProfileState.accept(new BuilderProfilePacket(BuilderProfilePacket.Status.OK,
            List.of(entry(41, false), entry(42, true)), "me", "Brennan", true));
        EditorScreenActions.Icon submit = EditorScreenActions.submitIcon(41);
        assertTrue(submit.enabled());
        assertEquals("submit", submit.id());
        assertEquals(EditorScreenLang.ICON_SUBMIT, submit.labelKey());

        EditorScreenActions.Icon withdraw = EditorScreenActions.submitIcon(42);
        assertTrue(withdraw.enabled());
        assertEquals("withdraw", withdraw.id());
        assertEquals(EditorScreenLang.ICON_WITHDRAW, withdraw.labelKey());

        // Somebody else's profile must not speak for this player's builds.
        BuilderProfileState.clearCache();
        BuilderProfileState.accept(new BuilderProfilePacket(BuilderProfilePacket.Status.OK,
            List.of(entry(41, true)), "them", "", false));
        assertFalse(EditorScreenActions.submitIcon(41).enabled());
        BuilderProfileState.clearCache();
    }

    private static BuilderProfilePacket.Entry entry(int relayId, boolean published) {
        return new BuilderProfilePacket.Entry(relayId, "carriage", "", "cabin", published, "", "", "",
            0, false, "me", "Brennan");
    }

    @Test
    @DisplayName("Undo and Redo name the step they would apply, and switch off with an empty stack")
    void historyIcons() {
        EditorScreenActions.Icon live = EditorScreenActions.historyIcon("undo",
            EditorScreenLang.ICON_UNDO, "dungeontrain editor undo",
            "Place", EditorScreenLang.UNDO_NOTHING);
        assertTrue(live.enabled());
        assertEquals("dungeontrain editor undo", command(live.entry()));
        assertEquals("Place", live.detail());

        EditorScreenActions.Icon empty = EditorScreenActions.historyIcon("redo",
            EditorScreenLang.ICON_REDO, "dungeontrain editor redo", "", EditorScreenLang.REDO_NOTHING);
        assertFalse(empty.enabled());
        assertNull(empty.detail());
        assertEquals(EditorScreenLang.REDO_NOTHING, empty.disabledKey());
    }

    // ---- enter / test ----

    @Test
    @DisplayName("enter runs the enter command; across categories it switches first, with no prompt in between")
    void enter() {
        VariantKey contents = VariantKey.of(PlotCategory.CONTENTS, "armor", "armor");
        EditorTypeMenusPacket.Variant v = gated("CONTENTS", "armor", "armor", 5, List.of());
        CommandMenuEntry same = EditorScreenActions.enterEntry(ctx(contents, v, null, PlotCategory.CONTENTS));
        assertEquals("dungeontrain editor contents enter armor", ((CommandMenuEntry.Run) same).command());
        // Across categories it is a client action that sends both commands — no save prompt, which
        // listed every plot the scan could see rather than the ones actually edited.
        CommandMenuEntry cross = EditorScreenActions.enterEntry(ctx(contents, v, null, PlotCategory.CARRIAGES));
        assertInstanceOf(CommandMenuEntry.ClientAction.class, cross);
        // Parts stamp with carriages, so from a carriage plot a part is a same-category enter.
        VariantKey part = VariantKey.of(PlotCategory.PARTS, "floor", "oak");
        EditorTypeMenusPacket.Variant pv = new EditorTypeMenusPacket.Variant("oak", -1, "PARTS", "floor", "oak", false, false);
        assertInstanceOf(CommandMenuEntry.Run.class, EditorScreenActions.enterEntry(ctx(part, pv, null, PlotCategory.CARRIAGES)));
        assertNull(EditorScreenActions.enterEntry(ctx(null, null, null, null)));
    }

    @Test
    @DisplayName("Test the Carriage is offered for any selected dimension room, wherever the author stands")
    void testCarriage() {
        VariantKey room = VariantKey.of(PlotCategory.PORTALS, "portal_room", "house");
        EditorTypeMenusPacket.Variant v = gated("PORTALS", "portal_room", "house", 1, List.of());

        CommandMenuEntry inside = EditorScreenActions.testEntry(ctx(room, v, room, PlotCategory.PORTALS));
        assertInstanceOf(PortalTestSaveCheckScreen.class, ((CommandMenuEntry.DrillIn) inside).target());

        // Standing in a different room, or in no plot at all: the test stamps its own band in the
        // basement either way, so where the author is standing is not part of what it tests.
        CommandMenuEntry elsewhere = EditorScreenActions.testEntry(
            ctx(room, v, VariantKey.of(PlotCategory.PORTALS, "portal_room", "beam"), PlotCategory.PORTALS));
        assertInstanceOf(PortalTestSaveCheckScreen.class, ((CommandMenuEntry.DrillIn) elsewhere).target());
        CommandMenuEntry nowhere = EditorScreenActions.testEntry(ctx(room, v, null, PlotCategory.PORTALS));
        assertInstanceOf(PortalTestSaveCheckScreen.class, ((CommandMenuEntry.DrillIn) nowhere).target());

        // Still dimensions only — nothing else has a room to stand up.
        VariantKey carriage = VariantKey.of(PlotCategory.CARRIAGES, "pen", "pen");
        assertNull(EditorScreenActions.testEntry(ctx(carriage, gated("CARRIAGES", "pen", "pen", 1, List.of()), carriage, PlotCategory.CARRIAGES)));
        assertNull(EditorScreenActions.testEntry(ctx(null, null, null, null)));
    }

    // ---- settings rows ----

    private static final Function<EditorScreenActions.Ctx, List<CommandMenuEntry>> ROWS =
        c -> EditorScreenActions.settingRows(c, () -> List.of(new CommandMenuEntry.Label("ROOM")), () -> "");

    @Test
    @DisplayName("the rows below the icons carry only what the sheet has no line for")
    void carriageRows() {
        VariantKey k = VariantKey.of(PlotCategory.CARRIAGES, "pen", "pen");
        List<CommandMenuEntry> rows = ROWS.apply(ctx(k, gated("CARRIAGES", "pen", "pen", 15, List.of()), null, PlotCategory.CARRIAGES));
        // Weight, the level bounds, the phases and the Stage link all moved onto the data sheet —
        // a Stage is a spawn gate, so it shares that line. Only the allow-list has no line.
        assertEquals(1, rows.size(), rows.stream().map(CommandMenuEntry::label).toList().toString());
        assertNotNull(rows.get(0));
    }

    @Test
    @DisplayName("a room the author is only looking at gets its rows from the roster, sent to the named root")
    void roomRowsFromRosterWhenNotStanding() {
        VariantKey room = VariantKey.of(PlotCategory.PORTALS, "portal_room", "labrynth");
        EditorTypeMenusPacket.Variant v = gated("PORTALS", "portal_room", "labrynth", 3, List.of());
        EditorScreenActions.Ctx looking = new EditorScreenActions.Ctx(room, v, 1, null, PlotCategory.PORTALS, false,
            new EditorRosterIndex.Extras("endless_repetition/dynamic", 11, 13, 7, EditorStatusPacket.NO_FLIP));
        List<CommandMenuEntry> rows = EditorScreenActions.roomRows(looking);
        List<String> labels = rows.stream().map(CommandMenuEntry::label).toList();
        assertTrue(labels.stream().anyMatch(l -> l.startsWith("Fog: Auto (On)")), labels.toString());
        assertTrue(labels.stream().anyMatch(l -> l.startsWith("Copies")), labels.toString());
        CommandMenuEntry.Stay fog = rows.stream().filter(r -> r.label().startsWith("Fog"))
            .map(r -> (CommandMenuEntry.Stay) r).findFirst().orElseThrow();
        assertEquals("dungeontrain editor portals room labrynth fog next", fog.command());
        // The settings list keeps them (minus the size steppers the sheet carries) — no standing gate.
        List<CommandMenuEntry> settings = EditorScreenActions.settingRows(looking,
            () -> rows, () -> EditorScreenActions.roomModeOf(looking, () -> ""));
        assertTrue(settings.stream().anyMatch(r -> r.label().startsWith("Fog")));
        assertTrue(settings.stream().noneMatch(r -> r.label().startsWith("Length")));
        assertEquals("endless_repetition/dynamic", EditorScreenActions.roomModeOf(looking, () -> "stood"));

        // No extras (a roster from before, or a sub-variant) — nothing to build from, so no rows.
        assertTrue(EditorScreenActions.roomRows(ctx(room, v, null, PlotCategory.PORTALS)).isEmpty());
    }

    @Test
    @DisplayName("a contents template the author is only looking at shows its Flip quad from the roster")
    void flipRowsFromRoster() {
        VariantKey k = VariantKey.of(PlotCategory.CONTENTS, "fire", "fire");
        EditorTypeMenusPacket.Variant v = gated("CONTENTS", "fire", "fire", 2, List.of());
        EditorScreenActions.Ctx looking = new EditorScreenActions.Ctx(k, v, 1, null, PlotCategory.CONTENTS, false,
            new EditorRosterIndex.Extras(EditorStatusPacket.NO_MODE, -1, -1, -1,
                EditorStatusPacket.FLIP_KNOWN | EditorStatusPacket.FLIP_Z));
        List<CommandMenuEntry> rows = ROWS.apply(looking);
        assertEquals("Flip", rows.get(0).label(), rows.toString());
        CommandMenuEntry.Quad quad = assertInstanceOf(CommandMenuEntry.Quad.class, rows.get(1));
        assertFalse(((CommandMenuEntry.Toggle) quad.e1()).state());
        assertTrue(((CommandMenuEntry.Toggle) quad.e3()).state());
        // Without the known bit there is nothing to show — same as the old roster.
        assertTrue(ROWS.apply(ctx(k, v, null, PlotCategory.CONTENTS)).isEmpty());
    }

    @Test
    @DisplayName("a stage-linked template drops the Stage row, because the Spawns line already is one")
    void stageLinkedRows() {
        VariantKey k = VariantKey.of(PlotCategory.CONTENTS, "fire", "fire");
        List<CommandMenuEntry> rows = ROWS.apply(ctx(k, gated("CONTENTS", "fire", "fire", 2, List.of("nether")), null, PlotCategory.CONTENTS));
        assertTrue(rows.isEmpty(), rows.toString());
    }

    @Test
    @DisplayName("a sub-variant's weight uses its group's verb, on the sheet")
    void subVariantWeight() {
        VariantKey member = new VariantKey(PlotCategory.CONTENTS, "armor5", "armor5", "armor");
        CommandMenuEntry.Triple weight = assertInstanceOf(CommandMenuEntry.Triple.class,
            EditorScreenActions.weightRow(member, 6));
        assertEquals("dungeontrain editor contents group set-weight armor armor5 inc",
            ((CommandMenuEntry.Stay) weight.rightEntry()).command());
        assertEquals("dungeontrain editor contents group set-weight armor armor5",
            ((CommandMenuEntry.TypeArg) weight.middleEntry()).commandPrefix());

        VariantKey roomMember = new VariantKey(PlotCategory.PORTALS, "portal_room", "evilhouse", "house");
        CommandMenuEntry.Triple rw = assertInstanceOf(CommandMenuEntry.Triple.class,
            EditorScreenActions.weightRow(roomMember, 1));
        assertEquals("dungeontrain editor portals group set-weight house evilhouse dec",
            ((CommandMenuEntry.Stay) rw.leftEntry()).command());

        // A sub-variant has no rows of its own left: its weight is the sheet's Weight line.
        EditorTypeMenusPacket.Variant v = new EditorTypeMenusPacket.Variant("armor5", 6, "CONTENTS", "armor5", "armor5", false, false);
        assertTrue(ROWS.apply(ctx(member, v, null, PlotCategory.CONTENTS)).isEmpty());
    }

    @Test
    @DisplayName("the room rows the pane is handed land under the icons, standing in the room or not")
    void roomRowsOnlyWhenStanding() {
        VariantKey room = VariantKey.of(PlotCategory.PORTALS, "portal_room", "house");
        EditorTypeMenusPacket.Variant v = gated("PORTALS", "portal_room", "house", 1, List.of());
        List<CommandMenuEntry> inside = ROWS.apply(ctx(room, v, room, PlotCategory.PORTALS));
        assertTrue(inside.stream().anyMatch(e -> "ROOM".equals(e.label())),
            "the room's non-size rows still belong under the icons");
        // Whether there ARE rows for a room the author is only looking at is roomRows' question
        // (see roomRowsFromRosterWhenNotStanding); settingRows keeps whatever it is handed.
        List<CommandMenuEntry> away = ROWS.apply(ctx(room, v, null, PlotCategory.PORTALS));
        assertTrue(away.stream().anyMatch(e -> "ROOM".equals(e.label())));
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

    @Test
    @DisplayName("remove on a contents parent: the three-way confirmation, one reset mode per row")
    void removeOnContentsParentOffersThreeWays() {
        EditorTypeMenusPacket.Variant copper = gated("CONTENTS", "copper", "copper", 2, List.of());
        EditorTypeMenusPacket.Variant stone = gated("CONTENTS", "stone", "stone", 1, List.of());
        EditorTypeMenusPacket.Variant maze = new EditorTypeMenusPacket.Variant("maze", 5, 10, 60, 1, "CONTENTS",
            "maze", "maze", false, false, List.of(copper, stone), List.of());
        VariantKey sel = VariantKey.of(PlotCategory.CONTENTS, "maze", "maze");

        CommandMenuEntry.DrillIn remove = assertInstanceOf(CommandMenuEntry.DrillIn.class,
            EditorScreenActions.removeEntry(ctx(sel, maze, null, null)));
        ParentRemoveConfirmScreen screen = assertInstanceOf(ParentRemoveConfirmScreen.class, remove.target());

        assertEquals("Remove 'maze'? It has 2 sub-variants", screen.title());
        List<CommandMenuEntry> rows = screen.entries();
        assertEquals(4, rows.size());
        assertEquals("dungeontrain editor contents reset maze all",
            assertInstanceOf(CommandMenuEntry.Run.class, rows.get(0)).command());
        assertEquals("dungeontrain editor contents reset maze unparent",
            assertInstanceOf(CommandMenuEntry.Run.class, rows.get(1)).command());
        CommandMenuEntry.Run promote = assertInstanceOf(CommandMenuEntry.Run.class, rows.get(2));
        assertEquals("dungeontrain editor contents reset maze promote", promote.command());
        assertTrue(promote.label().contains("'copper'"), "promote row names the first sub-variant");
        assertInstanceOf(CommandMenuEntry.Back.class, rows.get(3));
    }

    @Test
    @DisplayName("remove on a portal-room parent: same screen over the kind-addressed reset")
    void removeOnPortalRoomParent() {
        EditorTypeMenusPacket.Variant hall = gated("PORTALS", "portal_room", "hall", 1, List.of());
        EditorTypeMenusPacket.Variant house = new EditorTypeMenusPacket.Variant("house", 3, 10, 60, 1, "PORTALS",
            "portal_room", "house", false, false, List.of(hall), List.of());
        VariantKey sel = VariantKey.of(PlotCategory.PORTALS, "portal_room", "house");

        CommandMenuEntry.DrillIn remove = assertInstanceOf(CommandMenuEntry.DrillIn.class,
            EditorScreenActions.removeEntry(ctx(sel, house, null, null)));
        ParentRemoveConfirmScreen screen = assertInstanceOf(ParentRemoveConfirmScreen.class, remove.target());
        assertEquals("Remove 'house'? It has 1 sub-variant", screen.title());
        assertEquals("dungeontrain editor portals reset portal_room house promote",
            assertInstanceOf(CommandMenuEntry.Run.class, screen.entries().get(2)).command());
    }

    @Test
    @DisplayName("remove on a portal-room leaf: plain confirm, addressed by room name not by where the player stands")
    void removeOnPortalRoomLeafIsAddressed() {
        EditorTypeMenusPacket.Variant hall = gated("PORTALS", "portal_room", "hall", 1, List.of());
        CommandMenuEntry.DrillIn remove = assertInstanceOf(CommandMenuEntry.DrillIn.class,
            EditorScreenActions.removeEntry(ctx(VariantKey.of(PlotCategory.PORTALS, "portal_room", "hall"), hall, null, null)));
        ConfirmScreen screen = assertInstanceOf(ConfirmScreen.class, remove.target());
        assertEquals("dungeontrain editor portals reset portal_room hall",
            assertInstanceOf(CommandMenuEntry.Run.class, screen.entries().get(0)).command());
    }

    @Test
    @DisplayName("remove on a leaf or a sub-variant keeps the plain Yes/No")
    void removeOnLeafStaysPlain() {
        EditorTypeMenusPacket.Variant leaf = gated("CONTENTS", "armor5", "armor5", 6, List.of());
        CommandMenuEntry.DrillIn remove = assertInstanceOf(CommandMenuEntry.DrillIn.class,
            EditorScreenActions.removeEntry(ctx(VariantKey.of(PlotCategory.CONTENTS, "armor5", "armor5"), leaf, null, null)));
        assertInstanceOf(ConfirmScreen.class, remove.target());

        EditorTypeMenusPacket.Variant sub = gated("CONTENTS", "copper", "copper", 2, List.of());
        CommandMenuEntry.DrillIn subRemove = assertInstanceOf(CommandMenuEntry.DrillIn.class,
            EditorScreenActions.removeEntry(ctx(new VariantKey(PlotCategory.CONTENTS, "copper", "copper", "maze"), sub, null, null)));
        assertInstanceOf(ConfirmScreen.class, subRemove.target());
    }

    // ---- Train Builder host ----

    private static EditorScreenActions.Ctx builderCtx(VariantKey sel, EditorTypeMenusPacket.Variant v,
                                                      VariantKey standing) {
        return new EditorScreenActions.Ctx(sel, v, EditorPlotLabelsPacket.NO_WEIGHT, standing, null, false,
            EditorRosterIndex.Extras.NONE, EditorScreenActions.Host.BUILDER);
    }

    @Test
    @DisplayName("builder, build on the platform selected: Save is the builder's packet, Reset a re-open, Clear off")
    void builderIconsOnThePlatform() {
        VariantKey k = VariantKey.of(PlotCategory.CARRIAGES, "windowed", "windowed");
        EditorScreenActions.Ctx c = builderCtx(k, gated("CARRIAGES", "windowed", "windowed", 20, List.of()), k);
        List<CustomPacketPayload> sent = new ArrayList<>();
        Map<String, EditorScreenActions.Icon> icons = iconsById(c, sent);
        assertEquals(List.of("save", "rename", "move", "remove", "undo", "redo", "reset", "clear", "submit"),
            new ArrayList<>(icons.keySet()));
        assertTrue(icons.get("save").enabled());
        ((CommandMenuEntry.ClientAction) icons.get("save").entry()).action().run();
        assertEquals(1, sent.size());
        assertInstanceOf(BuilderSavePacket.class, sent.get(0));
        assertTrue(icons.get("reset").enabled());
        assertInstanceOf(CommandMenuEntry.ClientAction.class, icons.get("reset").entry());
        assertFalse(icons.get("clear").enabled());
        // Addressed by id, so the same as the editor's.
        assertInstanceOf(CommandMenuEntry.TypeArg.class, icons.get("rename").entry());
        assertNotNull(icons.get("remove").entry());
        assertFalse(icons.get("undo").enabled());
    }

    @Test
    @DisplayName("builder, another tile selected: Save, Reset and Clear are off and never the addressed packet")
    void builderIconsElsewhere() {
        VariantKey here = VariantKey.of(PlotCategory.CARRIAGES, "windowed", "windowed");
        VariantKey k = VariantKey.of(PlotCategory.CARRIAGES, "pen", "pen");
        EditorScreenActions.Ctx c = builderCtx(k, gated("CARRIAGES", "pen", "pen", 20, List.of()), here);
        List<CustomPacketPayload> sent = new ArrayList<>();
        Map<String, EditorScreenActions.Icon> icons = iconsById(c, sent);
        for (String id : List.of("save", "reset", "clear")) {
            assertFalse(icons.get(id).enabled(), id);
        }
        assertEquals(EditorScreenLang.DISABLED_OPEN_IN_BUILDER, icons.get("save").disabledKey());
        assertEquals(EditorScreenLang.DISABLED_OPEN_IN_BUILDER, icons.get("reset").disabledKey());
        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("builder: Enter is an open of the tile, and nothing when it is already on the platform")
    void builderEnter() {
        VariantKey contents = VariantKey.of(PlotCategory.CONTENTS, "armor", "armor");
        EditorTypeMenusPacket.Variant v = gated("CONTENTS", "armor", "armor", 5, List.of());
        CommandMenuEntry open = EditorScreenActions.enterEntry(builderCtx(contents, v, null));
        assertInstanceOf(CommandMenuEntry.ClientAction.class, open);
        assertNull(EditorScreenActions.enterEntry(builderCtx(contents, v, contents)));
        // A category the builder cannot hold has no open.
        VariantKey arch = VariantKey.of(PlotCategory.ARCHITECTURE, "x", "x");
        EditorTypeMenusPacket.Variant av = gated("ARCHITECTURE", "x", "x", 1, List.of());
        assertNull(EditorScreenActions.enterEntry(builderCtx(arch, av, null)));
    }

    @Test
    @DisplayName("builder: no Test the Carriage, and a stood-in room still reads its rows from the roster")
    void builderTestAndRoomRows() {
        VariantKey room = VariantKey.of(PlotCategory.PORTALS, "portal_room", "house");
        EditorTypeMenusPacket.Variant v = gated("PORTALS", "portal_room", "house", 1, List.of());
        assertNull(EditorScreenActions.testEntry(builderCtx(room, v, room)));
        EditorScreenActions.Ctx standing = builderCtx(room, v, room);
        assertTrue(standing.standingInSelection());
        assertFalse(standing.hudFresh(), "the builder pushes no status packet");
        // No roster extras either → no rows, rather than the stood-in rows the editor would show.
        assertTrue(EditorScreenActions.roomRows(standing).isEmpty());
    }
}
