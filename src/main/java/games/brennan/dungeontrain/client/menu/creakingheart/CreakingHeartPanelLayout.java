package games.brennan.dungeontrain.client.menu.creakingheart;

import games.brennan.dungeontrain.client.menu.ClientStages;
import games.brennan.dungeontrain.client.menu.MenuLang;
import games.brennan.dungeontrain.compat.CreakingHeartSettings;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;

import java.util.ArrayList;
import java.util.List;

/**
 * The creaking heart panel's layout, in panel-local block units (origin at the anchor, x right,
 * y up), built fresh each frame from the heart's settings. The renderer draws these cells and the
 * raycast hit-tests the same list, so the two can never disagree. Sizes and palette follow the Z
 * (block-variant) menu.
 *
 * <pre>
 *  Creaking Heart                 [X]
 *  [Works by Day ] [Works at Night ]
 *  Number of Creaking          [ 3 ]
 *  [Stage: Custom ▾                ]
 *  [Min Lv (0)   ] [Max Lv (all)   ]     Custom only
 *  [O][N][V][E]…  band grid, 2 rows      Custom only
 * </pre>
 */
public final class CreakingHeartPanelLayout {

    public enum Kind { NONE, BACKDROP, CLOSE, DAY, NIGHT, COUNT, STAGE, MIN, MAX, BAND, STAGE_OPTION }

    public record Hit(Kind kind, int index) {
        public static final Hit NONE = new Hit(Kind.NONE, -1);
    }

    /** One drawn rectangle; {@link Kind#NONE} cells are labels and never hover. */
    public record Cell(Kind kind, int index, double x1, double y1, double x2, double y2,
                       String text, int bg, int bgHover, int fg, boolean centered) {
        boolean contains(double x, double y) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2;
        }
    }

    /** The whole panel: its backdrop and every cell, plus the popup's backdrop when one is open. */
    public record Layout(double x1, double y1, double x2, double y2, List<Cell> cells,
                         double popupY1, double popupY2) {
        boolean hasPopup() { return popupY2 > popupY1; }
    }

    static final double TEXT_SCALE = 0.010;
    static final double HALF_W = 0.80;
    static final double ROW = 0.22;
    static final double PAD = 0.05;
    static final double GAP = 0.02;
    static final double BAND_ROWS = 2;

    static final int BACKDROP = 0xC8000000;
    static final int POPUP_BACKDROP = 0xE0202020;
    static final int HEADER_BAND = 0x40FFEEBB;
    static final int HEADER_TEXT = 0xFFFFEEBB;
    static final int CELL = 0x30FFFFFF;
    static final int CELL_HOVER = 0xB0FFCC33;
    static final int NUMBER = 0x40FFFFFF;
    static final int NUMBER_HOVER = 0xC0FFCC33;
    static final int ON = 0x80CC7733;
    static final int ON_HOVER = 0xC0FFAA55;
    static final int CLOSE = 0x40FF6060;
    static final int CLOSE_HOVER = 0xC0FF8080;
    static final int SELECTED = 0x808A4FB3;
    static final int SELECTED_HOVER = 0xC0D98CFF;
    static final int OPTION = 0x30777777;
    static final int OPTION_HOVER = 0x60AAAAAA;
    static final int TEXT = 0xFFFFFFFF;
    static final int TEXT_ON_HOVER = 0xFF000000;
    static final int BAND_ON_TEXT = 0xFF66FF66;
    static final int BAND_OFF_TEXT = 0xFF777777;

    private CreakingHeartPanelLayout() {}

    static Layout build(CreakingHeartSettings s, CreakingHeartPanel.Popup popup, List<ClientStages.Info> stages) {
        boolean custom = s.stageId().isEmpty();
        int bandCols = (TrainPhase.values().length + 1) / 2;
        int rows = 4 + (custom ? 1 + (int) BAND_ROWS : 0);
        double height = rows * ROW + (rows - 1) * GAP + 2 * PAD;
        double top = height / 2;
        double bottom = -height / 2;
        double left = -HALF_W;
        double right = HALF_W;
        double innerL = left + PAD;
        double innerR = right - PAD;
        double mid = (innerL + innerR) / 2;

        List<Cell> cells = new ArrayList<>();
        double y = top - PAD;

        // Header: title + close.
        cells.add(new Cell(Kind.NONE, -1, innerL, y - ROW, innerR, y, MenuLang.t("creaking_heart.title"),
            HEADER_BAND, HEADER_BAND, HEADER_TEXT, false));
        cells.add(new Cell(Kind.CLOSE, -1, innerR - ROW, y - ROW, innerR, y, "X", CLOSE, CLOSE_HOVER, TEXT, true));
        y -= ROW + GAP;

        // Day / Night.
        cells.add(toggle(Kind.DAY, innerL, y, mid - GAP / 2, MenuLang.t("creaking_heart.day"), s.day()));
        cells.add(toggle(Kind.NIGHT, mid + GAP / 2, y, innerR, MenuLang.t("creaking_heart.night"), s.night()));
        y -= ROW + GAP;

        // Number of Creaking.
        double numL = innerR - 0.30;
        cells.add(new Cell(Kind.NONE, -1, innerL, y - ROW, numL - GAP, y, MenuLang.t("creaking_heart.count_title"),
            0, 0, TEXT, false));
        cells.add(new Cell(Kind.COUNT, -1, numL, y - ROW, innerR, y, Integer.toString(s.count()),
            NUMBER, NUMBER_HOVER, TEXT, true));
        y -= ROW + GAP;

        // Stage.
        boolean stageOpen = popup == CreakingHeartPanel.Popup.STAGE;
        cells.add(new Cell(Kind.STAGE, -1, innerL, y - ROW, innerR, y,
            MenuLang.t("creaking_heart.stage", stageName(s.stageId(), stages)) + (stageOpen ? " ▴" : " ▾"),
            stageOpen ? ON : CELL, stageOpen ? ON_HOVER : CELL_HOVER, TEXT, false));
        y -= ROW + GAP;

        if (custom) {
            TemplateGate g = s.gate();
            String max = g.maxLevel() < 0 ? MenuLang.t("editor.max_level_all") : Integer.toString(g.maxLevel());
            cells.add(new Cell(Kind.MIN, -1, innerL, y - ROW, mid - GAP / 2, y,
                MenuLang.t("editor.min_level", g.minLevel()), NUMBER, NUMBER_HOVER, TEXT, true));
            cells.add(new Cell(Kind.MAX, -1, mid + GAP / 2, y - ROW, innerR, y,
                MenuLang.t("editor.max_level", max), NUMBER, NUMBER_HOVER, TEXT, true));
            y -= ROW + GAP;

            double bandW = (innerR - innerL - (bandCols - 1) * GAP) / bandCols;
            TrainPhase[] phases = TrainPhase.values();
            for (int i = 0; i < phases.length; i++) {
                int col = i % bandCols;
                int row = i / bandCols;
                double x1 = innerL + col * (bandW + GAP);
                double cy = y - row * (ROW + GAP);
                boolean on = g.phases().contains(phases[i]);
                cells.add(new Cell(Kind.BAND, i, x1, cy - ROW, x1 + bandW, cy, phases[i].letter(),
                    CELL, CELL_HOVER, on ? BAND_ON_TEXT : BAND_OFF_TEXT, true));
            }
        }

        double popupY1 = 0;
        double popupY2 = 0;
        if (stageOpen) {
            // Stacked above the panel, Span/Growth popup style: Custom, then every stage.
            List<String> labels = new ArrayList<>();
            labels.add(MenuLang.t("creaking_heart.custom"));
            for (ClientStages.Info info : stages) labels.add(info.name());
            popupY1 = top + GAP;
            double py = popupY1 + PAD + labels.size() * ROW + (labels.size() - 1) * GAP;
            popupY2 = py + PAD;
            for (int i = 0; i < labels.size(); i++) {
                boolean selected = i == 0 ? custom : stages.get(i - 1).id().equals(s.stageId());
                cells.add(new Cell(Kind.STAGE_OPTION, i, innerL, py - ROW, innerR, py, labels.get(i),
                    selected ? SELECTED : OPTION, selected ? SELECTED_HOVER : OPTION_HOVER, TEXT, false));
                py -= ROW + GAP;
            }
        }
        return new Layout(left, bottom, right, top, cells, popupY1, popupY2);
    }

    /** What is under panel-local ({@code x}, {@code y}): a cell, the backdrop, or nothing. */
    static Hit hitFor(Layout layout, double x, double y) {
        for (Cell c : layout.cells()) {
            if (c.kind() != Kind.NONE && c.contains(x, y)) return new Hit(c.kind(), c.index());
        }
        boolean onPanel = x >= layout.x1() && x <= layout.x2() && y >= layout.y1() && y <= layout.y2();
        boolean onPopup = layout.hasPopup() && x >= layout.x1() && x <= layout.x2()
            && y >= layout.popupY1() && y <= layout.popupY2();
        return onPanel || onPopup ? new Hit(Kind.BACKDROP, -1) : Hit.NONE;
    }

    private static Cell toggle(Kind kind, double x1, double y, double x2, String label, boolean on) {
        return new Cell(kind, -1, x1, y - ROW, x2, y, label, on ? ON : CELL, on ? ON_HOVER : CELL_HOVER, TEXT, true);
    }

    private static String stageName(String stageId, List<ClientStages.Info> stages) {
        if (stageId.isEmpty()) return MenuLang.t("creaking_heart.custom");
        for (ClientStages.Info info : stages) {
            if (info.id().equals(stageId)) return info.name();
        }
        return stageId;
    }
}
