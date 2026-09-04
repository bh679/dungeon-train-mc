package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.builder.TemplateSummary;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.client.menu.MenuScreen;
import games.brennan.dungeontrain.client.menu.StagePickerScreen;
import games.brennan.dungeontrain.client.menu.plot.EditorPlotTeleport;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * What the selected build is, and where you change it.
 *
 * <p>The sheet is the editing surface rather than a read-out with a matching set of controls
 * underneath. A weight, a level bound, a phase and a room's dimensions are all edited on the line
 * that shows them: click a number to type over it, click a phase letter to toggle it, and the
 * weight carries its own pair of nudge buttons. The pane used to show each of those twice — once
 * as a fact and once as a stepper row — and the two could disagree.</p>
 *
 * <p>Command strings are never written here. Every editable cell takes its command from the same
 * builders the old menu's rows use ({@code EditorMenuScreen.weightTripleFor} and friends), read
 * back out of the row they return, so the two surfaces cannot drift apart.</p>
 */
public final class TemplateDataSheet {

    static final int LABEL = 0xFFFFEEBB;
    static final int VALUE = 0xFFFFFFFF;
    static final int VALUE_OFF = 0x70FFFFFF;
    static final int LINE_H = 10;
    static final int CELL_GAP = 3;
    static final int LABEL_GAP = 6;

    /** What clicking an editable cell does. */
    public sealed interface Action {
        /** Type a value over the cell; the command is {@code prefix + " " + typed}. */
        record Type(String prefix) implements Action {}

        /** Run a command and leave the menu open. */
        record Run(String command) implements Action {}

        /** Open a screen as a modal. */
        record Open(MenuScreen screen) implements Action {}
    }

    /**
     * One run of text on a line.
     *
     * @param action null for plain text, which is never clickable
     * @param on     whether the cell reads as set — a phase that is off is dimmed
     */
    public record Cell(String text, Action action, boolean on, String tooltip) {
        public static Cell plain(String text) {
            return new Cell(text, null, true, null);
        }

        public Cell(String text, Action action, boolean on) {
            this(text, action, on, null);
        }

        public Cell withTooltip(String tooltip) {
            return new Cell(text, action, on, tooltip);
        }
    }

    public record Line(String label, List<Cell> cells) {
        public static Line of(String label, String value) {
            return new Line(label, List.of(Cell.plain(value)));
        }
    }

    /** A cell with the rectangle it was drawn in, so a click finds what it sees. */
    public record Placed(Cell cell, InventoryEditorLayout.Rect rect) {}

    private TemplateDataSheet() {}

    // ------------------------------------------------------------------
    // Lines for an editor template
    // ------------------------------------------------------------------

    /**
     * The sheet for the selected template.
     *
     * @param roomRows the portal-room geometry rows, whose length, width and height become the
     *                 editable Size line; empty for every other category
     */
    public static List<Line> lines(EditorRosterIndex.Tile tile, String pathLabel, TemplateSummary summary,
                                   EditorRosterIndex.Provenance provenance, VariantKey key,
                                   List<CommandMenuEntry> roomRows) {
        List<Line> out = new ArrayList<>(6);
        if (tile == null) return out;
        EditorTypeMenusPacket.Variant v = tile.variant();
        String pending = EditorScreenLang.text(EditorScreenLang.SHEET_PENDING);

        out.add(Line.of(EditorScreenLang.text(EditorScreenLang.SHEET_PATH), pathLabel));
        out.add(sizeLine(summary, roomRows, key, pending));
        out.add(Line.of(EditorScreenLang.text(EditorScreenLang.SHEET_BLOCKS), blocks(summary, pending)));
        out.add(weightLine(tile, key, pending));
        out.addAll(stageLines(v, key, pending));
        out.add(Line.of(EditorScreenLang.text(EditorScreenLang.SHEET_SOURCE), sourceLabel(provenance)));
        return out;
    }

    /**
     * Size: a dimension's own box, or the train footprint every other build is cut to.
     *
     * <p>The two are different things wearing the same word. A room's box belongs to that room. A
     * carriage's does not belong to the carriage — it is the train's, shared by every carriage,
     * part and track in the world — so typing here resizes all of them, and the cell says so on
     * hover before it is touched.</p>
     */
    static Line sizeLine(TemplateSummary summary, List<CommandMenuEntry> roomRows, VariantKey key,
                         String pending) {
        String label = EditorScreenLang.text(EditorScreenLang.SHEET_SIZE);
        List<Cell> roomCells = roomSizeCells(roomRows);
        if (!roomCells.isEmpty()) return new Line(label, roomCells);
        if (key != null && key.category() != null && key.category() != PlotCategory.PORTALS) {
            List<Cell> trainCells = trainSizeCells();
            if (!trainCells.isEmpty()) return new Line(label, trainCells);
        }
        if (summary == null || summary.isEmpty()) return Line.of(label, pending);
        var s = summary.declaredSize();
        return Line.of(label, s.getX() + " × " + s.getY() + " × " + s.getZ());
    }

    /**
     * The train's own footprint as three typeable cells, or empty until the roster has arrived
     * with it.
     */
    private static List<Cell> trainSizeCells() {
        EditorRosterPacket.TrainSize dims = EditorRosterClient.index().trainSize();
        if (!dims.isKnown()) return List.of();
        String warning = EditorScreenLang.text(EditorScreenLang.SHEET_TRAIN_SIZE);
        List<Cell> cells = new ArrayList<>(5);
        cells.add(trainAxis("length", dims.length(), warning));
        cells.add(Cell.plain("×"));
        cells.add(trainAxis("width", dims.width(), warning));
        cells.add(Cell.plain("×"));
        cells.add(trainAxis("height", dims.height(), warning));
        return cells;
    }

    private static Cell trainAxis(String axis, int value, String warning) {
        return new Cell(Integer.toString(value),
            new Action.Type("dungeontrain editor size " + axis), true).withTooltip(warning);
    }

    /** The three room dimensions as typeable cells, read out of the rows the old menu builds. */
    private static List<Cell> roomSizeCells(List<CommandMenuEntry> roomRows) {
        List<Cell> cells = new ArrayList<>(5);
        for (CommandMenuEntry row : roomRows) {
            Stepper stepper = Stepper.of(row);
            if (stepper == null || !stepper.isRoomAxis()) continue;
            if (!cells.isEmpty()) cells.add(Cell.plain("×"));
            cells.add(new Cell(stepper.value(), new Action.Type(stepper.prefix()), true)
                .withTooltip(stepper.axisName()));
        }
        return cells;
    }

    static String blocks(TemplateSummary summary, String pending) {
        if (summary == null || summary.isEmpty()) return pending;
        StringBuilder blocks = new StringBuilder(Integer.toString(summary.blocks()));
        if (summary.entities() > 0) {
            blocks.append(" · ").append(EditorScreenLang.text(EditorScreenLang.SHEET_ENTITIES, summary.entities()));
        }
        if (summary.containers() > 0) {
            blocks.append(" · ").append(EditorScreenLang.text(EditorScreenLang.SHEET_CONTAINERS, summary.containers()));
        }
        return blocks.toString();
    }

    /** Weight: the number types, and a pair of nudge buttons sits after it. */
    static Line weightLine(EditorRosterIndex.Tile tile, VariantKey key, String pending) {
        String label = EditorScreenLang.text(EditorScreenLang.SHEET_WEIGHT);
        EditorTypeMenusPacket.Variant v = tile.variant();
        int weight = v.weight();
        if (weight == EditorPlotLabelsPacket.NO_WEIGHT || key == null) return Line.of(label, pending);

        Stepper stepper = Stepper.of(EditorScreenActions.weightRow(key, weight));
        List<Cell> cells = new ArrayList<>(4);
        if (stepper == null) {
            cells.add(Cell.plain(Integer.toString(weight)));
        } else {
            cells.add(new Cell(Integer.toString(weight), new Action.Type(stepper.prefix()), true)
                .withTooltip(EditorScreenLang.text(EditorScreenLang.SHEET_WEIGHT_TOOLTIP)));
            cells.add(new Cell("-", new Action.Run(stepper.dec()), true)
                .withTooltip(EditorScreenLang.text(EditorScreenLang.SHEET_WEIGHT_DOWN)));
            cells.add(new Cell("+", new Action.Run(stepper.inc()), true)
                .withTooltip(EditorScreenLang.text(EditorScreenLang.SHEET_WEIGHT_UP)));
        }
        if (tile.isGroup()) {
            cells.add(Cell.plain(EditorScreenLang.text(EditorScreenLang.SHEET_SHARE, v.subVariants().size())));
        }
        return new Line(label, cells);
    }

    /**
     * Stage: which preset the template is on, and the levels and dimensions it spawns across.
     *
     * <p>One line, because they are one thing. A Stage <em>is</em> a named spawn gate, so showing
     * "Stage" and "Spawns" separately said the same fact twice under two names. The stage cell
     * opens the picker — including when it reads Custom, which is how a template gets linked in
     * the first place — and while a Stage owns the gate the bounds and letters beside it are
     * shown but not editable, since the Stage would set them back.</p>
     */
    static List<Line> stageLines(EditorTypeMenusPacket.Variant v, VariantKey key, String pending) {
        String label = EditorScreenLang.text(EditorScreenLang.SHEET_STAGE);
        if (v.phaseMask() == EditorTypeMenusPacket.Variant.NO_GATE || key == null) {
            return List.of(Line.of(label, pending));
        }
        boolean linked = v.isStageLinked();

        String stageName = linked ? v.primaryStageId()
            : EditorScreenLang.text(EditorScreenLang.STAGE_CUSTOM_SHORT);
        Cell stage = new Cell(stageName,
            new Action.Open(new StagePickerScreen(key.category(), key.modelId(), key.modelName(),
                linked ? v.primaryStageId() : "")), true)
            .withTooltip(EditorScreenLang.text(EditorScreenLang.SHEET_STAGE_TOOLTIP));

        List<Cell> cells = new ArrayList<>(11);
        cells.add(Cell.plain("Lv"));
        addLevelCell(cells, key, "minlevel", Integer.toString(v.minLevel()), linked,
            EditorScreenLang.SHEET_MIN_LEVEL);
        cells.add(Cell.plain("—"));
        addLevelCell(cells, key, "maxlevel", v.maxLevel() < 0
                ? EditorScreenLang.text(EditorScreenLang.SHEET_LEVELS_ALL) : Integer.toString(v.maxLevel()),
            linked, EditorScreenLang.SHEET_MAX_LEVEL);
        cells.add(Cell.plain("·"));
        for (TrainPhase p : TrainPhase.values()) {
            boolean on = (v.phaseMask() & p.bit()) != 0;
            String letter = String.valueOf(Character.toUpperCase(p.name().charAt(0)));
            String phaseName = phaseName(p);
            String command = linked ? null : EditorPlotTeleport.phaseCommandFor(key.category(),
                key.modelId(), key.modelName(), p.token(), on ? "off" : "on");
            Cell cell = command == null
                ? new Cell(letter, null, on)
                : new Cell(letter, new Action.Run(command), on);
            cells.add(cell.withTooltip(phaseName));
        }
        // Custom means the bounds and letters are all live, which is too much to sit beside the
        // stage name — so they take a line of their own. A linked Stage's are read-only and fit.
        return linked
            ? List.of(new Line(label, prepend(stage, cells)))
            : List.of(new Line(label, List.of(stage)), new Line("", cells));
    }

    /** {@code first} followed by {@code rest} — one line's worth of cells. */
    private static List<Cell> prepend(Cell first, List<Cell> rest) {
        List<Cell> all = new ArrayList<>(rest.size() + 1);
        all.add(first);
        all.addAll(rest);
        return all;
    }

    /** A dimension's name, capitalised and un-underscored: {@code UPSIDE_DOWN} → "Upside down". */
    static String phaseName(TrainPhase p) {
        String n = p.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static void addLevelCell(List<Cell> cells, VariantKey key, String sub, String shown,
                                     boolean linked, String tooltipKey) {
        Stepper stepper = linked ? null : Stepper.of(EditorScreenActions.levelRow(key, sub, shown));
        Cell cell = stepper == null ? new Cell(shown, null, true)
            : new Cell(shown, new Action.Type(stepper.prefix()), true);
        cells.add(cell.withTooltip(EditorScreenLang.text(tooltipKey)));
    }

    static String sourceLabel(EditorRosterIndex.Provenance p) {
        return switch (p) {
            case BUILTIN -> EditorScreenLang.text(EditorScreenLang.SOURCE_BUILTIN);
            case USER -> EditorScreenLang.text(EditorScreenLang.SOURCE_MINE);
            case IMPORTED -> EditorScreenLang.text(EditorScreenLang.SOURCE_COMMUNITY);
        };
    }

    // ------------------------------------------------------------------
    // Lines for an uploaded build
    // ------------------------------------------------------------------

    /**
     * The lines for one of the player's uploaded builds.
     *
     * <p>Different facts, and none of them editable from here: a relay build has no spawn weight
     * and no plot, and what matters about it is what kind it is, where it stands with the reviewer,
     * and whether the copy on this machine has drifted from the uploaded one.</p>
     */
    public static List<Line> buildLines(BuilderProfilePacket.Entry entry, TemplateSummary summary) {
        List<Line> out = new ArrayList<>(5);
        if (entry == null) return out;
        String pending = EditorScreenLang.text(EditorScreenLang.SHEET_PENDING);
        out.add(Line.of(EditorScreenLang.text(EditorScreenLang.SHEET_TYPE), EditorMyBuildsPane.typeLabel(entry)));
        out.add(Line.of(EditorScreenLang.text(EditorScreenLang.SHEET_STATUS), EditorMyBuildsPane.statusLabel(entry)));
        if (summary == null || summary.isEmpty()) {
            out.add(Line.of(EditorScreenLang.text(EditorScreenLang.SHEET_SIZE), pending));
            out.add(Line.of(EditorScreenLang.text(EditorScreenLang.SHEET_BLOCKS), pending));
        } else {
            var s = summary.declaredSize();
            out.add(Line.of(EditorScreenLang.text(EditorScreenLang.SHEET_SIZE),
                s.getX() + " × " + s.getY() + " × " + s.getZ()));
            out.add(Line.of(EditorScreenLang.text(EditorScreenLang.SHEET_BLOCKS),
                Integer.toString(summary.blocks())));
        }
        if (entry.changes() > 0) {
            out.add(Line.of(EditorScreenLang.text(EditorScreenLang.SHEET_CHANGES),
                Integer.toString(entry.changes())));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Layout, paint, hit-test
    // ------------------------------------------------------------------

    /** Place every cell, so what is drawn and what a click lands on come from one list. */
    public static List<Placed> place(List<Line> lines, InventoryEditorLayout.Rect r, Font font) {
        int labelW = 0;
        for (Line l : lines) labelW = Math.max(labelW, font.width(l.label()));
        List<Placed> placed = new ArrayList<>();
        int y = r.y() + 1;
        for (Line line : lines) {
            if (y + font.lineHeight > r.bottom()) break;
            int x = r.x() + 2 + labelW + LABEL_GAP;
            for (Cell cell : line.cells()) {
                int w = font.width(cell.text());
                if (x + w > r.right()) break;
                placed.add(new Placed(cell, new InventoryEditorLayout.Rect(x, y - 1, w + 2, LINE_H)));
                x += w + CELL_GAP;
            }
            y += LINE_H;
        }
        return placed;
    }

    public static void draw(GuiGraphics g, Font font, InventoryEditorLayout.Rect r, List<Line> lines,
                            List<Placed> placed, int hovered) {
        int labelW = 0;
        for (Line l : lines) labelW = Math.max(labelW, font.width(l.label()));
        int y = r.y() + 1;
        for (Line line : lines) {
            if (y + font.lineHeight > r.bottom()) break;
            g.drawString(font, line.label(), r.x() + 2, y, LABEL, false);
            y += LINE_H;
        }
        for (int i = 0; i < placed.size(); i++) {
            Placed p = placed.get(i);
            Cell cell = p.cell();
            boolean clickable = cell.action() != null;
            boolean hot = clickable && i == hovered;
            if (hot) {
                g.fill(p.rect().x() - 1, p.rect().y(), p.rect().right() - 1, p.rect().bottom(),
                    MenuRowPainter.CELL_HOVER);
            } else if (clickable) {
                g.fill(p.rect().x() - 1, p.rect().bottom() - 1, p.rect().right() - 1, p.rect().bottom(),
                    0x40FFFFFF);
            }
            int colour = hot ? MenuRowPainter.TEXT_ON_HOVER : (cell.on() ? VALUE : VALUE_OFF);
            g.drawString(font, cell.text(), p.rect().x(), p.rect().y() + 1, colour, false);
        }
    }

    /** Which placed cell is under the point and clickable, or -1. */
    public static int hit(List<Placed> placed, double mx, double my) {
        for (int i = 0; i < placed.size(); i++) {
            Placed p = placed.get(i);
            if (p.cell().action() != null && p.rect().contains(mx, my)) return i;
        }
        return -1;
    }

    /**
     * A stepper row taken apart: its typing prefix and its two nudge commands.
     *
     * <p>Reading these back out of the row the old menu builds is what keeps one definition of each
     * command shape. Nothing here spells a command out.</p>
     */
    record Stepper(String prefix, String dec, String inc, String value, String label) {

        static Stepper of(CommandMenuEntry row) {
            if (!(row instanceof CommandMenuEntry.Triple t)) return null;
            if (!(t.middleEntry() instanceof CommandMenuEntry.TypeArg middle)) return null;
            if (!(t.leftEntry() instanceof CommandMenuEntry.Stay left)) return null;
            if (!(t.rightEntry() instanceof CommandMenuEntry.Stay right)) return null;
            return new Stepper(middle.commandPrefix(), left.command(), right.command(),
                valueIn(middle.label()), middle.label());
        }

        /** The axis this row sets, for its cell's tooltip: the label without its value. */
        String axisName() {
            int open = label.indexOf('(');
            return open > 0 ? label.substring(0, open).trim() : label;
        }

        /** A room axis row is the one whose command sets length, width or height. */
        boolean isRoomAxis() {
            return prefix.contains(" portals length") || prefix.contains(" portals width")
                || prefix.contains(" portals height");
        }

        /** The number inside a label like {@code "Weight (20)"}, or the whole label without one. */
        private static String valueIn(String label) {
            int open = label.indexOf('(');
            int close = label.lastIndexOf(')');
            return open >= 0 && close > open ? label.substring(open + 1, close) : label;
        }
    }
}
