package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.GroupParentPickerScreen;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.client.menu.StagePickerScreen;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Layout tab's spawn table, row by row, against a small roster.
 *
 * <p>Cells are fold · name · − · value · + · stage · move, so the name is cell 1 and the move cell 6.</p>
 */
final class EditorLayoutPageTest {

    private static final int NO_GATE = EditorTypeMenusPacket.Variant.NO_GATE;
    private static final int FOLD = 0, NAME = 1, DEC = 2, VALUE = 3, INC = 4, STAGE = 5, MOVE = 6;

    private static EditorTypeMenusPacket.Variant v(String name, String cat, String modelId, String modelName,
                                                   int weight, int phaseMask, List<String> stages,
                                                   List<EditorTypeMenusPacket.Variant> subs) {
        return new EditorTypeMenusPacket.Variant(name, weight, 0, -1, phaseMask, cat, modelId, modelName,
            false, false, subs, stages);
    }

    private static EditorRosterPacket.Entry e(EditorTypeMenusPacket.Variant variant, int self) {
        return new EditorRosterPacket.Entry(variant, self);
    }

    static EditorRosterIndex sample() {
        var windowed = v("windowed", "CARRIAGES", "windowed", "windowed", 20, 1, List.of("nether"), List.of());
        var oakFloor = v("oak", "PARTS", "floor", "oak", EditorPlotLabelsPacket.NO_WEIGHT, NO_GATE, List.of(), List.of());
        var armor5 = v("armor5", "CONTENTS", "armor5", "armor5", 6, NO_GATE, List.of(), List.of());
        var armor = v("armor", "CONTENTS", "armor", "armor", 2, 1, List.of(), List.of(armor5));
        var evilhouse = v("evilhouse", "PORTALS", "portal_room", "evilhouse", 3, 1, List.of("nether", "desert"), List.of());
        var house = v("house", "PORTALS", "portal_room", "house", 1, 1, List.of(), List.of(evilhouse));
        var fancy = v("fancy", "TRACKS", "pillar_top", "fancy", 5, 1, List.of(), List.of());
        var plain = v("plain", "TRACKS", "pillar_top", "plain", 4, 1, List.of(), List.of(fancy));
        return new EditorRosterIndex(List.of(
            new EditorRosterPacket.Group("carriages", "Carriages", "", List.of(e(windowed, EditorPlotLabelsPacket.NO_WEIGHT))),
            new EditorRosterPacket.Group("parts", "Floor", "floor", List.of(e(oakFloor, EditorPlotLabelsPacket.NO_WEIGHT))),
            new EditorRosterPacket.Group("contents", "Contents", "", List.of(e(armor, 2))),
            new EditorRosterPacket.Group("portals", "Dimensional Carriage", "portal_room", List.of(e(house, EditorPlotLabelsPacket.NO_WEIGHT))),
            new EditorRosterPacket.Group("tracks", "Pillar Top", "pillar_top", List.of(e(plain, EditorPlotLabelsPacket.NO_WEIGHT)))),
            "contents", new EditorRosterPacket.TrainSize(9, 7, 7));
    }

    private static final class Recorder {
        final List<VariantKey> selected = new ArrayList<>();
        final List<String> toggled = new ArrayList<>();
        final List<String> groups = new ArrayList<>();
    }

    private static List<EditorLayoutPage.Row> rows(Recorder rec, Set<String> collapsed) {
        return EditorLayoutPage.rows(sample(), collapsed, rec.selected::add, rec.toggled::add);
    }

    private static List<EditorLayoutPage.Row> rows(Recorder rec, EditorLayoutPage.Folds folds, EditorLayoutPage.Query q) {
        return EditorLayoutPage.rows(sample(), folds, q, rec.selected::add, rec.toggled::add, rec.groups::add);
    }

    private static CommandMenuEntry[] cellsOf(EditorLayoutPage.Row row) {
        return MenuRowPainter.cellsOf(row.entry());
    }

    private static EditorLayoutPage.Row rowFor(List<EditorLayoutPage.Row> rows, String modelName, int depth) {
        for (EditorLayoutPage.Row r : rows) {
            if (!r.isHeader() && r.depth() == depth && r.key().modelName().equals(modelName)) return r;
        }
        throw new AssertionError("no row for " + modelName + " at depth " + depth + " in " + rows);
    }

    private static List<String> sections(List<EditorLayoutPage.Row> rows) {
        return rows.stream().filter(EditorLayoutPage.Row::isHeader).map(EditorLayoutPage.Row::sectionId).toList();
    }

    private static List<EditorLayoutPage.Row> query(EditorLayoutPage.Query q) {
        return EditorLayoutPage.rows(sample(), Set.of(), q, k -> { }, s -> { });
    }

    private static List<String> names(List<EditorLayoutPage.Row> rows, String sectionId) {
        return rows.stream().filter(r -> r.sectionId().equals(sectionId) && !r.isHeader())
            .map(r -> r.key().modelName()).toList();
    }

    @Test
    @DisplayName("sections follow roster order; a header toggles its own id")
    void sections() {
        Recorder rec = new Recorder();
        List<EditorLayoutPage.Row> rows = rows(rec, Set.of());
        assertEquals(List.of("carriages/Carriages", "parts/Floor", "contents/Contents",
            "portals/Dimensional Carriage", "tracks/Pillar Top"), sections(rows));
        EditorLayoutPage.Row first = rows.get(0);
        // No language is loaded here, so the header reads as its key plus the arrow — which is
        // still exactly what the page said it would be.
        assertEquals(EditorLayoutPage.OPEN + " " + EditorScreenLang.text(EditorScreenLang.LAYOUT_SECTION, "Carriages", 1),
            first.entry().label());
        ((CommandMenuEntry.ClientAction) first.entry()).action().run();
        assertEquals(List.of("carriages/Carriages"), rec.toggled);
    }

    @Test
    @DisplayName("a folded section is its header alone, marked folded")
    void folded() {
        List<EditorLayoutPage.Row> rows = rows(new Recorder(), Set.of("contents/Contents"));
        List<EditorLayoutPage.Row> contents = rows.stream().filter(r -> r.sectionId().equals("contents/Contents")).toList();
        assertEquals(1, contents.size());
        assertTrue(contents.get(0).isHeader());
        assertTrue(contents.get(0).entry().label().startsWith(EditorLayoutPage.FOLDED + " "));
        // The other sections are untouched.
        assertEquals(3, rows.stream().filter(r -> r.sectionId().equals("portals/Dimensional Carriage")).count());
    }

    @Test
    @DisplayName("a carriage row: blank fold, name selects, weight steps and types, stage opens its picker, no move")
    void carriageRow() {
        Recorder rec = new Recorder();
        EditorLayoutPage.Row row = rowFor(rows(rec, Set.of()), "windowed", 0);
        assertFalse(row.isGroupParent());
        CommandMenuEntry[] c = cellsOf(row);
        assertEquals(7, c.length);
        assertInstanceOf(CommandMenuEntry.Label.class, c[FOLD]);
        assertEquals("windowed", c[NAME].label());
        ((CommandMenuEntry.ClientAction) c[NAME]).action().run();
        assertEquals(List.of(VariantKey.of(PlotCategory.CARRIAGES, "windowed", "windowed")), rec.selected);

        CommandMenuEntry.Stay dec = assertInstanceOf(CommandMenuEntry.Stay.class, c[DEC]);
        CommandMenuEntry.TypeArg value = assertInstanceOf(CommandMenuEntry.TypeArg.class, c[VALUE]);
        CommandMenuEntry.Stay inc = assertInstanceOf(CommandMenuEntry.Stay.class, c[INC]);
        assertTrue(dec.command().contains("windowed") && dec.command().endsWith("dec"), dec.command());
        assertTrue(inc.command().contains("windowed") && inc.command().endsWith("inc"), inc.command());
        assertEquals("20", value.label());
        assertTrue(value.commandPrefix().contains("windowed"), value.commandPrefix());

        CommandMenuEntry.DrillIn stage = assertInstanceOf(CommandMenuEntry.DrillIn.class, c[STAGE]);
        assertEquals("nether", stage.label());
        assertInstanceOf(StagePickerScreen.class, stage.target());
        assertInstanceOf(CommandMenuEntry.Label.class, c[MOVE]);
    }

    @Test
    @DisplayName("a part has no weight pool and no gate: blanks and a dash")
    void partRow() {
        EditorLayoutPage.Row row = rowFor(rows(new Recorder(), Set.of()), "oak", 0);
        CommandMenuEntry[] c = cellsOf(row);
        for (int i = DEC; i <= MOVE; i++) assertInstanceOf(CommandMenuEntry.Label.class, c[i], "cell " + i);
        assertEquals(EditorScreenLang.text(EditorScreenLang.SHEET_PENDING), c[STAGE].label());
        // Only the name answers a click: the fold cell is blank on a plain row, and so is everything after.
        assertEquals(-1, MenuRowPainter.hitCell(row.entry(), 2, 0, 100));
        assertEquals(NAME, MenuRowPainter.hitCell(row.entry(), 20, 0, 100));
        assertEquals(-1, MenuRowPainter.hitCell(row.entry(), 95, 0, 100));
    }

    @Test
    @DisplayName("a contents group: the parent with a fold cell, its own share as (self), then its member one step in")
    void contentsGroup() {
        Recorder rec = new Recorder();
        List<EditorLayoutPage.Row> rows = rows(rec, EditorLayoutPage.Folds.NONE, EditorLayoutPage.Query.EVERYTHING);
        List<EditorLayoutPage.Row> section = rows.stream()
            .filter(r -> r.sectionId().equals("contents/Contents") && !r.isHeader()).toList();
        assertEquals(List.of(0, 1, 1), section.stream().map(EditorLayoutPage.Row::depth).toList());

        EditorLayoutPage.Row parent = section.get(0);
        assertTrue(parent.isGroupParent());
        assertEquals("contents/armor/armor", parent.groupId());
        CommandMenuEntry fold = cellsOf(parent)[FOLD];
        assertEquals(EditorLayoutPage.OPEN, fold.label());
        ((CommandMenuEntry.ClientAction) fold).action().run();
        assertEquals(List.of("contents/armor/armor"), rec.groups);

        EditorLayoutPage.Row self = section.get(1);
        assertFalse(self.isGroupParent());
        CommandMenuEntry[] sc = cellsOf(self);
        assertInstanceOf(CommandMenuEntry.Label.class, sc[FOLD]);
        assertEquals(EditorScreenLang.text(EditorScreenLang.TILE_SELF, "armor"), sc[NAME].label());
        assertEquals("dungeontrain editor contents group set-weight armor armor", ((CommandMenuEntry.TypeArg) sc[VALUE]).commandPrefix());
        assertEquals("2", sc[VALUE].label());
        ((CommandMenuEntry.ClientAction) sc[NAME]).action().run();
        assertEquals(VariantKey.of(PlotCategory.CONTENTS, "armor", "armor"), rec.selected.get(0), "(self) selects the parent");
        assertInstanceOf(CommandMenuEntry.Label.class, sc[STAGE]);
        assertInstanceOf(CommandMenuEntry.Label.class, sc[MOVE]);

        EditorLayoutPage.Row member = section.get(2);
        assertEquals("armor", member.key().parentId());
        CommandMenuEntry[] mc = cellsOf(member);
        assertEquals("dungeontrain editor contents group set-weight armor armor5", ((CommandMenuEntry.TypeArg) mc[VALUE]).commandPrefix());
        assertTrue(((CommandMenuEntry.Stay) mc[INC]).command().contains("armor5"));
        assertEquals(EditorScreenLang.text(EditorScreenLang.STAGE_CUSTOM_SHORT), mc[STAGE].label());
        CommandMenuEntry.DrillIn move = assertInstanceOf(CommandMenuEntry.DrillIn.class, mc[MOVE]);
        GroupParentPickerScreen picker = assertInstanceOf(GroupParentPickerScreen.class, move.target());
        assertEquals("dungeontrain editor contents group remove armor armor5", picker.promoteCommand());
    }

    @Test
    @DisplayName("a folded group keeps its parent, marked folded, and hides (self) and its members")
    void foldedGroup() {
        Recorder rec = new Recorder();
        List<EditorLayoutPage.Row> rows = rows(rec, new EditorLayoutPage.Folds(Set.of(), Set.of("contents/armor/armor")),
            EditorLayoutPage.Query.EVERYTHING);
        assertEquals(List.of("armor"), names(rows, "contents/Contents"));
        EditorLayoutPage.Row parent = rowFor(rows, "armor", 0);
        assertEquals(EditorLayoutPage.FOLDED, cellsOf(parent)[FOLD].label());
        // Other groups are untouched.
        assertEquals(List.of("house", "evilhouse"), names(rows, "portals/Dimensional Carriage"));
    }

    @Test
    @DisplayName("a search shows what it matched even inside a folded group")
    void searchOpensFoldedGroup() {
        Recorder rec = new Recorder();
        List<EditorLayoutPage.Row> rows = rows(rec, new EditorLayoutPage.Folds(Set.of(), Set.of("contents/armor/armor")),
            new EditorLayoutPage.Query(EditorCategoryFilter.ALL, "", EditorRosterIndex.Filters.NONE, "armor5"));
        assertEquals(List.of("armor", "armor", "armor5"), names(rows, "contents/Contents"));
        assertEquals(EditorLayoutPage.OPEN, cellsOf(rowFor(rows, "armor", 0))[FOLD].label());
    }

    @Test
    @DisplayName("a room member: the portals weight verb, both stages on its chip, and a move to another room")
    void portalMember() {
        EditorLayoutPage.Row member = rowFor(rows(new Recorder(), Set.of()), "evilhouse", 1);
        CommandMenuEntry[] c = cellsOf(member);
        assertEquals("dungeontrain editor portals group set-weight house evilhouse", ((CommandMenuEntry.TypeArg) c[VALUE]).commandPrefix());
        assertEquals("nether +1", c[STAGE].label());
        GroupParentPickerScreen picker = (GroupParentPickerScreen) ((CommandMenuEntry.DrillIn) c[MOVE]).target();
        assertEquals("dungeontrain editor portals group move evilhouse book", picker.moveCommand("book"));
    }

    @Test
    @DisplayName("a track member has no weight verb yet: the number is read-only, and there is no move")
    void trackMember() {
        EditorLayoutPage.Row member = rowFor(rows(new Recorder(), Set.of()), "fancy", 1);
        CommandMenuEntry[] c = cellsOf(member);
        assertInstanceOf(CommandMenuEntry.Label.class, c[DEC]);
        assertEquals("5", c[VALUE].label());
        assertInstanceOf(CommandMenuEntry.Label.class, c[VALUE]);
        assertInstanceOf(CommandMenuEntry.Label.class, c[INC]);
        assertInstanceOf(CommandMenuEntry.DrillIn.class, c[STAGE]);
        assertInstanceOf(CommandMenuEntry.Label.class, c[MOVE]);
    }

    @Test
    @DisplayName("the category cell narrows the sections; Carriages keeps the part kinds")
    void categoryNarrows() {
        var none = EditorRosterIndex.Filters.NONE;
        assertEquals(List.of("carriages/Carriages", "parts/Floor"),
            sections(query(new EditorLayoutPage.Query(EditorCategoryFilter.CARRIAGES, "", none, ""))));
        assertEquals(List.of("portals/Dimensional Carriage"),
            sections(query(new EditorLayoutPage.Query(EditorCategoryFilter.DIMENSIONS, "", none, ""))));
        assertEquals(5, sections(query(EditorLayoutPage.Query.EVERYTHING)).size());
    }

    @Test
    @DisplayName("a chosen type strip leaves one section; under All the type is ignored")
    void typeNarrows() {
        var none = EditorRosterIndex.Filters.NONE;
        assertEquals(List.of("parts/Floor"),
            sections(query(new EditorLayoutPage.Query(EditorCategoryFilter.CARRIAGES, "Floor", none, ""))));
        assertEquals(5, sections(query(new EditorLayoutPage.Query(EditorCategoryFilter.ALL, "Floor", none, ""))).size());
    }

    @Test
    @DisplayName("the search keeps a parent whose member matches, and drops sections it empties")
    void textNarrows() {
        List<EditorLayoutPage.Row> rows = query(new EditorLayoutPage.Query(EditorCategoryFilter.ALL, "",
            EditorRosterIndex.Filters.NONE, "armor5"));
        assertEquals(List.of("contents/Contents"), sections(rows));
        // parent, (self), the one matching member
        assertEquals(List.of("armor", "armor", "armor5"), names(rows, "contents/Contents"));
        assertTrue(rows.get(0).entry().label().endsWith(EditorScreenLang.text(EditorScreenLang.LAYOUT_SECTION, "Contents", 1)));
    }

    @Test
    @DisplayName("the provenance chips narrow the top-level rows the way they narrow the tiles")
    void provenanceNarrows() {
        // Everything in the sample is built-in, so asking for Mine only leaves nothing.
        var mineOnly = EditorRosterIndex.Filters.NONE.withMine(true);
        assertTrue(query(new EditorLayoutPage.Query(EditorCategoryFilter.ALL, "", mineOnly, "")).isEmpty());
        var builtinOnly = EditorRosterIndex.Filters.NONE.withBuiltin(true);
        assertEquals(5, sections(query(new EditorLayoutPage.Query(EditorCategoryFilter.ALL, "", builtinOnly, ""))).size());
    }

    @Test
    @DisplayName("an empty roster has no rows; a null one neither")
    void empty() {
        assertTrue(EditorLayoutPage.rows(EditorRosterIndex.EMPTY, Set.of(), k -> { }, s -> { }).isEmpty());
        assertTrue(EditorLayoutPage.rows(null, Set.of(), k -> { }, s -> { }).isEmpty());
    }

    @Test
    @DisplayName("folding a section or a group replaces its set each time and folds back to nothing")
    void toggleReplacesTheSet() {
        Set<String> before = EditorScreenState.collapsedSections();
        EditorScreenState.toggleSection("contents/Contents");
        Set<String> once = EditorScreenState.collapsedSections();
        assertNotSame(before, once);
        assertTrue(once.contains("contents/Contents"));
        EditorScreenState.toggleSection("contents/Contents");
        Set<String> twice = EditorScreenState.collapsedSections();
        assertNotSame(once, twice);
        assertFalse(twice.contains("contents/Contents"));
        EditorScreenState.toggleSection(null);
        assertEquals(twice, EditorScreenState.collapsedSections());

        Set<String> g0 = EditorScreenState.collapsedGroups();
        EditorScreenState.toggleGroup("contents/armor/armor");
        Set<String> g1 = EditorScreenState.collapsedGroups();
        assertNotSame(g0, g1);
        assertTrue(g1.contains("contents/armor/armor"));
        EditorScreenState.toggleGroup("contents/armor/armor");
        assertFalse(EditorScreenState.collapsedGroups().contains("contents/armor/armor"));
        EditorScreenState.toggleGroup(null);
        assertNull(null);
    }
}
