package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.stagepalette.StagePaletteMenu;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.StagePaletteEditPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The stage preview's two palette pages as rows of placeholder cells: the Palette page (Solid,
 * Shapes and the Wood set under their own headings) and the Stone page (the stone family's seven
 * kinds, one labelled row each). Pure, so the row split can be pinned without a screen.
 *
 * <p>The placeholder names and their order come from the world-space Stage Palette panel's own
 * {@link StagePaletteMenu#LAYOUT}, so the two surfaces list the same 57 blocks the same way; only
 * the shape differs — that panel is a wide table, this is a narrow column, so each family's cells
 * wrap to however many fit across.</p>
 */
public final class EditorStagePalettePage {

    /** What a row is: a heading, a family heading (click sets the family), a run of cells, or a labelled run. */
    public enum Kind { HEADING, FAMILY, CELLS, LABELLED }

    /**
     * One row. {@code names} are placeholder names (empty for headings); {@code familyOp} is the
     * edit a FAMILY heading's click sends, null otherwise.
     */
    public record Row(Kind kind, String text, List<String> names, StagePaletteEditPacket.Op familyOp) {
        static Row heading(String text) {
            return new Row(Kind.HEADING, text, List.of(), null);
        }

        static Row family(String text, StagePaletteEditPacket.Op op) {
            return new Row(Kind.FAMILY, text, List.of(), op);
        }

        static Row cells(List<String> names) {
            return new Row(Kind.CELLS, "", List.copyOf(names), null);
        }

        static Row labelled(String label, List<String> names) {
            return new Row(Kind.LABELLED, label, List.copyOf(names), null);
        }

        public boolean isHeading() {
            return kind == Kind.HEADING || kind == Kind.FAMILY;
        }
    }

    /** Shown after a family name the author locked, so a re-bake will not re-detect it. */
    static final String LOCK = " 🔒";

    private EditorStagePalettePage() {}

    /** The Palette page: Solid 1–10, then the shapes, then the wood set, cells wrapped {@code cols} across. */
    public static List<Row> paletteRows(EditorRosterPacket.Palette palette, int cols) {
        List<String> solid = new ArrayList<>();
        List<String> shapes = new ArrayList<>();
        List<String> wood = new ArrayList<>();
        for (StagePaletteMenu.Row r : StagePaletteMenu.LAYOUT) {
            if (r.kind() != StagePaletteMenu.RowKind.CELLS) continue;
            if (r.label().startsWith("Solid")) {
                for (Map.Entry<StagePaletteMenu.Column, String> c : r.cells().entrySet()) {
                    (c.getKey() == StagePaletteMenu.Column.BLOCK ? solid : shapes).add(c.getValue());
                }
            } else if (r.label().equals("Wood")) {
                wood.addAll(r.cells().values());
            }
        }
        List<Row> out = new ArrayList<>();
        out.add(Row.heading(EditorScreenLang.text(EditorScreenLang.STAGES_PALETTE_SOLID)));
        out.addAll(wrap(solid, cols));
        out.add(Row.heading(EditorScreenLang.text(EditorScreenLang.STAGES_PALETTE_SHAPES)));
        out.addAll(wrap(shapes, cols));
        out.add(Row.family(familyText(EditorScreenLang.STAGES_PALETTE_WOOD, palette.wood(), palette.woodLocked()),
            StagePaletteEditPacket.Op.SET_WOOD));
        out.addAll(wrap(wood, cols));
        return out;
    }

    /** The Stone page: the family heading, then one labelled row per stone kind. */
    public static List<Row> stoneRows(EditorRosterPacket.Palette palette) {
        List<Row> out = new ArrayList<>();
        out.add(Row.family(familyText(EditorScreenLang.STAGES_PALETTE_STONE, palette.stone(), palette.stoneLocked()),
            StagePaletteEditPacket.Op.SET_STONE));
        for (StagePaletteMenu.Row r : StagePaletteMenu.LAYOUT) {
            if (r.kind() != StagePaletteMenu.RowKind.CELLS || !r.label().startsWith("  ")) continue;
            out.add(Row.labelled(r.label().trim(), new ArrayList<>(r.cells().values())));
        }
        return out;
    }

    /** {@code Wood: spruce} — with the lock after a family the author chose. */
    static String familyText(String labelKey, String family, boolean locked) {
        String text = EditorScreenLang.text(labelKey) + ": " + (family.isEmpty() ? "—" : family);
        return locked ? text + LOCK : text;
    }

    /** {@code names} as rows of at most {@code cols} cells, in order. */
    static List<Row> wrap(List<String> names, int cols) {
        int per = Math.max(1, cols);
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < names.size(); i += per) {
            out.add(Row.cells(names.subList(i, Math.min(names.size(), i + per))));
        }
        return out;
    }
}
