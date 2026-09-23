package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.MenuLang;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.client.builder.TemplateSummary;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.client.menu.MenuScreen;
import games.brennan.dungeontrain.client.menu.StagePickerScreen;
import games.brennan.dungeontrain.client.menu.plot.EditorPlotTeleport;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.editor.TemplateLoot;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import games.brennan.dungeontrain.net.EditorTypeMenusPacket;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What the selected build is, and where you change it.
 *
 * <p>The sheet is the editing surface rather than a read-out with a matching set of controls
 * underneath. A weight, a level bound, a phase and a room's dimensions are all edited on the line
 * that shows them: click a number to nudge it up (shift-click down, cmd-click to type over it),
 * click a phase letter to toggle it, and the weight carries its own pair of nudge buttons besides.
 * The pane used to show each of those twice — once as a fact and once as a stepper row — and the
 * two could disagree.</p>
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
    /** An item icon's side, and the height of a line that carries them. */
    static final int ICON = 16;
    static final int ICON_LINE_H = 18;
    /** The corner mark on an icon that may not spawn — a variant's loot block. */
    static final int VARIANT_MARK = 0xFFB070FF;

    /** What clicking an editable cell does. */
    public sealed interface Action {
        /** Type a value over the cell; the command is {@code prefix + " " + typed}. */
        record Type(String prefix) implements Action {}

        /** Run a command and leave the menu open. */
        record Run(String command) implements Action {}

        /**
         * A number that steps: click runs {@code inc}, shift-click runs {@code dec}, and cmd-click
         * types a value over the cell with {@code prefix} — the same three gestures as the weight
         * cell in the world-space menus and on the Layout tab.
         */
        record Step(String prefix, String dec, String inc) implements Action {
            static Step of(Stepper stepper) {
                return new Step(stepper.prefix(), stepper.dec(), stepper.inc());
            }
        }

        /** Open a screen as a modal. */
        record Open(MenuScreen screen) implements Action {}

        /**
         * Pick a builder from the relay and credit them; the command is
         * {@code prefix + " " + uuid + " " + name} (or {@code prefix + " none"} to clear).
         */
        record PickBuilder(String prefix) implements Action {}
    }

    /**
     * One run of text on a line, or one item icon.
     *
     * @param action null for plain text, which is never clickable
     * @param on     whether the cell reads as set — a phase that is off is dimmed; an icon that is
     *               off is one that may not spawn, and carries a corner mark
     * @param icon   drawn in place of {@code text} when present, with its count as a stack's is
     */
    public record Cell(String text, Action action, boolean on, String tooltip, ItemStack icon) {
        public static Cell plain(String text) {
            return new Cell(text, null, true, null, null);
        }

        /** An item icon, read-only; {@code text} is what it stands for, kept for tests and narration. */
        public static Cell icon(ItemStack icon, String text, boolean on) {
            return new Cell(text, null, on, null, icon);
        }

        public Cell(String text, Action action, boolean on) {
            this(text, action, on, null, null);
        }

        public Cell withTooltip(String tooltip) {
            return new Cell(text, action, on, tooltip, icon);
        }

        public boolean isIcon() {
            return icon != null && !icon.isEmpty();
        }
    }

    /** One labelled row; {@code height} is {@link #LINE_H} unless it carries icons. */
    public record Line(String label, List<Cell> cells, int height) {
        public Line(String label, List<Cell> cells) {
            this(label, cells, cells.stream().anyMatch(Cell::isIcon) ? ICON_LINE_H : LINE_H);
        }

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

        // The path used to open the sheet; the bands line under a Custom stage needed its row more.
        // A labelled build is drawn under its label everywhere else on this screen; the id is what
        // every command and file is named by, so the sheet keeps it one line away.
        if (v.isLabelled()) {
            out.add(Line.of(EditorScreenLang.text(EditorScreenLang.SHEET_ID), v.name()));
        }
        out.add(builderLine(v, key, EditorStatusHudOverlay.isDevModeOn()));
        out.add(sizeLine(summary, roomRows, key, pending));
        out.add(blocksLine(summary, pending));
        out.add(lightsLine(summary, pending));
        out.add(lootLine(summary, pending));
        out.add(weightLine(tile, key, pending));
        out.addAll(stageLines(v, key, pending));
        out.add(Line.of(EditorScreenLang.text(EditorScreenLang.SHEET_SOURCE), sourceLabel(provenance)));
        return out;
    }

    /**
     * Built by: who originally made this template.
     *
     * <p>Read-only in play. In dev mode the cell is a picker — a click opens the builder search and
     * the pick runs the kind's {@code builder} command — because crediting somebody is the
     * developer's call and the credit writes through to the source tree. Categories with no
     * {@code builder} verb (parts, tracks) show the value and nothing else.</p>
     */
    static Line builderLine(EditorTypeMenusPacket.Variant v, VariantKey key, boolean devMode) {
        return builderLine(v, devMode ? builderCommandPrefix(key) : null);
    }

    /** Built by: the credit, and — with a {@code builder} command prefix — the picker that sets it. */
    static Line builderLine(EditorTypeMenusPacket.Variant v, String prefix) {
        String label = EditorScreenLang.text(EditorScreenLang.SHEET_BUILDER);
        String shown = v.hasBuilder() ? v.builderDisplay()
            : EditorScreenLang.text(EditorScreenLang.SHEET_BUILDER_NONE);
        if (prefix == null) return Line.of(label, shown);
        return new Line(label, List.of(new Cell(shown, new Action.PickBuilder(prefix), v.hasBuilder())
            .withTooltip(EditorScreenLang.text(EditorScreenLang.SHEET_BUILDER_TOOLTIP))));
    }

    /**
     * The {@code builder} command for {@code key}, minus its {@code <uuid> [name]} tail; null for a
     * category without the verb. Mirrors {@code EditorScreenActions.renameEntry}'s spellings: a room
     * is addressed as {@code <kind> <name>}, everything else by id.
     */
    static String builderCommandPrefix(VariantKey key) {
        if (key == null) return null;
        return switch (key.category()) {
            case CARRIAGES -> "dungeontrain editor builder " + key.modelId();
            case CONTENTS -> "dungeontrain editor contents builder " + key.modelId();
            case PORTALS -> "dungeontrain editor portals builder " + key.modelId() + " " + key.modelName();
            case WHOLE -> "dungeontrain editor whole builder " + key.modelId();
            case WHOLE_GROUP -> "dungeontrain editor whole group builder " + key.modelId();
            case PARTS, TRACKS, ARCHITECTURE -> null;
        };
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

    /**
     * Blocks: the count, then the entity and container tallies each as a cell of its own.
     *
     * <p>Separate cells on purpose. {@link #place} drops a cell whole when it runs past the sheet's
     * right edge, and the pane can be as narrow as {@link InventoryEditorLayout#RIGHT_MIN_W}: as one
     * string, the first armour stand saved into a template pushed the value past that edge and the
     * whole count vanished. Split, the count always lands and only the trailing tallies give way.</p>
     */
    static Line blocksLine(TemplateSummary summary, String pending) {
        String label = EditorScreenLang.text(EditorScreenLang.SHEET_BLOCKS);
        if (summary == null || summary.isEmpty()) return Line.of(label, pending);
        List<Cell> cells = new ArrayList<>(3);
        cells.add(Cell.plain(Integer.toString(summary.blocks())));
        if (summary.entities() > 0) {
            cells.add(Cell.plain("· " + EditorScreenLang.text(EditorScreenLang.SHEET_ENTITIES, summary.entities())));
        }
        if (summary.containers() > 0) {
            cells.add(Cell.plain("· " + EditorScreenLang.text(EditorScreenLang.SHEET_CONTAINERS, summary.containers())));
        }
        return new Line(label, List.copyOf(cells));
    }

    /** Lights: how many blocks give off light. */
    static Line lightsLine(TemplateSummary summary, String pending) {
        String label = EditorScreenLang.text(EditorScreenLang.SHEET_LIGHTS);
        if (summary == null || summary.isEmpty()) return Line.of(label, pending);
        return Line.of(label, Integer.toString(summary.lights()));
    }

    /**
     * Loot: every loot block as its item's icon, most valuable first, a count on the icon when
     * there are several and a corner mark on one that only a variant may place.
     *
     * <p>Most valuable first because {@link #place} drops whatever runs past the sheet's edge: on
     * a narrow pane the cheapest go and the chest worth opening stays.</p>
     */
    static Line lootLine(TemplateSummary summary, String pending) {
        String label = EditorScreenLang.text(EditorScreenLang.SHEET_LOOT);
        if (summary == null || summary.isEmpty()) return Line.of(label, pending);
        if (summary.loot().isEmpty()) {
            return Line.of(label, EditorScreenLang.text(EditorScreenLang.SHEET_LOOT_NONE));
        }
        List<Cell> cells = new ArrayList<>(summary.loot().size());
        for (TemplateLoot.LootBlock loot : summary.loot()) cells.add(lootCell(loot));
        return new Line(label, List.copyOf(cells));
    }

    private static Cell lootCell(TemplateLoot.LootBlock loot) {
        String name = loot.block().getName().getString();
        ItemStack stack = new ItemStack(loot.block().asItem(), Math.max(1, loot.count()));
        Cell cell = stack.isEmpty()
            // A block with no item form (rare for a container) still gets a place in the row.
            ? new Cell(name, null, !loot.isVariant())
            : Cell.icon(stack, name, !loot.isVariant());
        return cell.withTooltip(lootTooltip(loot, name));
    }

    /** Name, where its loot comes from, its chance if a variant, its best items, and its value. */
    static String lootTooltip(TemplateLoot.LootBlock loot, String name) {
        StringBuilder sb = new StringBuilder(loot.count() > 1 ? name + " ×" + loot.count() : name);
        sb.append('\n').append(switch (loot.source()) {
            case POOL -> EditorScreenLang.text(EditorScreenLang.SHEET_LOOT_POOL);
            case PREFAB -> EditorScreenLang.text(EditorScreenLang.SHEET_LOOT_PREFAB, loot.detail());
            case INLINE -> EditorScreenLang.text(EditorScreenLang.SHEET_LOOT_INLINE);
            case TABLE -> EditorScreenLang.text(EditorScreenLang.SHEET_LOOT_TABLE, loot.detail());
            case DEFAULT -> EditorScreenLang.text(EditorScreenLang.SHEET_LOOT_DEFAULT, loot.detail(), loot.chance());
        });
        if (loot.isVariant()) {
            sb.append('\n').append(EditorScreenLang.text(EditorScreenLang.SHEET_LOOT_VARIANT, loot.chance()));
        }
        if (!loot.topItems().isEmpty()) {
            List<String> names = loot.topItems().stream()
                .map(i -> new ItemStack(i).getHoverName().getString()).toList();
            sb.append('\n').append(EditorScreenLang.text(EditorScreenLang.SHEET_LOOT_BEST, String.join(", ", names)));
        }
        sb.append('\n').append(EditorScreenLang.text(EditorScreenLang.SHEET_LOOT_VALUE,
            String.format(java.util.Locale.ROOT, "%.1f", loot.value())));
        return sb.toString();
    }

    /** Weight: the number steps (cmd-click types), and a pair of nudge buttons sits after it. */
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
            cells.add(new Cell(Integer.toString(weight), Action.Step.of(stepper), true)
                .withTooltip(EditorScreenLang.text(EditorScreenLang.LAYOUT_WEIGHT_TIP)));
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

        List<Cell> levels = levelCells(
            v.minLevel(), linked ? null : Stepper.of(EditorScreenActions.levelRow(key, "minlevel", Integer.toString(v.minLevel()))),
            v.maxLevel(), linked ? null : Stepper.of(EditorScreenActions.levelRow(key, "maxlevel",
                v.maxLevel() < 0 ? EditorScreenLang.text(EditorScreenLang.SHEET_LEVELS_ALL) : Integer.toString(v.maxLevel()))));
        PhaseCommand phases = linked ? null : (p, on) -> EditorPlotTeleport.phaseCommandFor(key.category(),
            key.modelId(), key.modelName(), p.token(), on ? "off" : "on");
        if (linked) {
            // A linked Stage owns the gate: its bounds and letters are read-only and fit beside the name.
            List<Cell> cells = prepend(stage, levels);
            cells.add(Cell.plain("·"));
            cells.addAll(bandCells(v.phaseMask(), null));
            return List.of(new Line(label, cells));
        }
        // Custom means every bound and letter is live, which is too much for one line — so the
        // bounds take the stage's line and the bands a row of their own, every letter a button.
        List<Cell> first = prepend(stage, levels);
        return List.of(new Line(label, first), bandsLine(v.phaseMask(), phases));
    }

    /** {@code Bands  O N V E U C}: every band its own letter button (or plain when read-only). */
    static Line bandsLine(int phaseMask, PhaseCommand phaseCommand) {
        return new Line(EditorScreenLang.text(EditorScreenLang.STAGES_BANDS), bandCells(phaseMask, phaseCommand));
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

    /** The command a phase letter sends: {@code on} is the letter's state before the click. */
    @FunctionalInterface
    interface PhaseCommand {
        String of(TrainPhase phase, boolean on);
    }

    /**
     * {@code Lv [min] — [max]}: a gate's bounds, shared by a template's Stage line and a Stage's own
     * sheet so the two read and click the same. A null stepper makes that bound read-only (a
     * template whose gate a Stage owns).
     */
    static List<Cell> levelCells(int minLevel, Stepper minStepper, int maxLevel, Stepper maxStepper) {
        List<Cell> cells = new ArrayList<>(4);
        cells.add(Cell.plain(MenuLang.t("sheet.level_short")));
        addLevelCell(cells, Integer.toString(minLevel), minStepper, EditorScreenLang.SHEET_MIN_LEVEL);
        cells.add(Cell.plain("—"));
        addLevelCell(cells, maxLevel < 0
                ? EditorScreenLang.text(EditorScreenLang.SHEET_LEVELS_ALL) : Integer.toString(maxLevel),
            maxStepper, EditorScreenLang.SHEET_MAX_LEVEL);
        return cells;
    }

    /** {@code O N V E U C}: one letter per band, lit when set; a null command makes them plain. */
    static List<Cell> bandCells(int phaseMask, PhaseCommand phaseCommand) {
        List<Cell> cells = new ArrayList<>(TrainPhase.values().length);
        for (TrainPhase p : TrainPhase.values()) {
            boolean on = (phaseMask & p.bit()) != 0;
            String letter = String.valueOf(Character.toUpperCase(p.name().charAt(0)));
            String command = phaseCommand == null ? null : phaseCommand.of(p, on);
            Cell cell = command == null
                ? new Cell(letter, null, on)
                : new Cell(letter, new Action.Run(command), on);
            cells.add(cell.withTooltip(phaseName(p)));
        }
        return cells;
    }

    private static void addLevelCell(List<Cell> cells, String shown, Stepper stepper, String tooltipKey) {
        String tooltip = EditorScreenLang.text(tooltipKey);
        if (stepper == null) {
            cells.add(new Cell(shown, null, true).withTooltip(tooltip));
            return;
        }
        // Live bounds step like a weight, so the tooltip carries the same gesture hint.
        cells.add(new Cell(shown, Action.Step.of(stepper), true)
            .withTooltip(tooltip + "\n" + EditorScreenLang.text(EditorScreenLang.LAYOUT_WEIGHT_TIP)));
    }

    static String sourceLabel(EditorRosterIndex.Provenance p) {
        return switch (p) {
            case BUILTIN -> EditorScreenLang.text(EditorScreenLang.SOURCE_BUILTIN);
            case USER -> EditorScreenLang.text(EditorScreenLang.SOURCE_MINE);
            case IMPORTED -> EditorScreenLang.text(EditorScreenLang.SOURCE_COMMUNITY);
        };
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
            if (y + Math.max(font.lineHeight, line.height() - 2) > r.bottom()) break;
            // A continuation line has no label, so it has no label column to clear either — it
            // starts at the left edge and gets the whole width for the row it carries on.
            int x = line.label().isEmpty() ? r.x() + 2 : r.x() + 2 + labelW + LABEL_GAP;
            // Text on a line made tall by icons sits on the icons' middle.
            int textY = y + (line.height() - LINE_H) / 2;
            for (Cell cell : line.cells()) {
                int w = cell.isIcon() ? ICON : font.width(cell.text());
                if (x + w > r.right()) break;
                InventoryEditorLayout.Rect rect = cell.isIcon()
                    ? new InventoryEditorLayout.Rect(x, y, ICON, ICON)
                    : new InventoryEditorLayout.Rect(x, textY - 1, w + 2, LINE_H);
                placed.add(new Placed(cell, rect));
                x += w + CELL_GAP;
            }
            y += line.height();
        }
        return placed;
    }

    public static void draw(GuiGraphics g, Font font, InventoryEditorLayout.Rect r, List<Line> lines,
                            List<Placed> placed, int hovered) {
        int labelW = 0;
        for (Line l : lines) labelW = Math.max(labelW, font.width(l.label()));
        int y = r.y() + 1;
        for (Line line : lines) {
            if (y + Math.max(font.lineHeight, line.height() - 2) > r.bottom()) break;
            g.drawString(font, line.label(), r.x() + 2, y + (line.height() - LINE_H) / 2, LABEL, false);
            y += line.height();
        }
        for (int i = 0; i < placed.size(); i++) {
            Placed p = placed.get(i);
            Cell cell = p.cell();
            if (cell.isIcon()) {
                drawIcon(g, font, p.rect(), cell);
                continue;
            }
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

    /** An icon cell: the item as a slot draws it, count included, and the variant mark when off. */
    private static void drawIcon(GuiGraphics g, Font font, InventoryEditorLayout.Rect rect, Cell cell) {
        g.renderItem(cell.icon(), rect.x(), rect.y());
        g.renderItemDecorations(font, cell.icon(), rect.x(), rect.y());
        if (!cell.on()) {
            // Above the item's own z so the mark is not hidden behind a block model.
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            g.fill(rect.x(), rect.y(), rect.x() + 4, rect.y() + 4, VARIANT_MARK);
            g.pose().popPose();
        }
    }

    /**
     * Which placed cell is under the point and clickable or has something to say on hover, or -1.
     * A read-only cell with a tooltip is hit too, so it can show its tooltip; a click on it does
     * nothing, as the sheet's click handlers ignore a cell with no action.
     */
    public static int hit(List<Placed> placed, double mx, double my) {
        for (int i = 0; i < placed.size(); i++) {
            Placed p = placed.get(i);
            boolean live = p.cell().action() != null || p.cell().tooltip() != null;
            if (live && p.rect().contains(mx, my)) return i;
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
            // Matched on the axis word rather than the whole root, so a stepper built for a named
            // room (`portals room <name> length`) is the same Size cell as a stood-in one.
            return prefix.endsWith(" length") || prefix.endsWith(" width") || prefix.endsWith(" height");
        }

        /** The number inside a label like {@code "Weight (20)"}, or the whole label without one. */
        private static String valueIn(String label) {
            int open = label.indexOf('(');
            int close = label.lastIndexOf(')');
            return open >= 0 && close > open ? label.substring(open + 1, close) : label;
        }
    }
}
