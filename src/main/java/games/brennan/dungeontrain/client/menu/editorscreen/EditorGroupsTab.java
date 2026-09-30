package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.builder.TemplateSummary;
import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.ConfirmScreen;
import games.brennan.dungeontrain.client.menu.MenuClickModifiers;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.client.menu.MenuScreen;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The Groups tab (Tracks only): every tunnel template group and what is in it.
 *
 * <p>The left column lists the groups — the implicit Ungrouped pool first, then each named group
 * with its roll weight and how many entrances / sections it has, marked when it cannot build a
 * tunnel — and a {@code + New group} row. The right column is the picked group, in three pages:</p>
 * <ul>
 *   <li><b>Group</b> — how many entrances and sections it has, each count followed by one icon per
 *       member (the member's most numerous block), then its name (Rename / Delete), roll weight
 *       and whether it can build a tunnel;</li>
 *   <li><b>Entrances</b> and <b>Sections</b> — every template of that kind, with a {@code [x]}
 *       toggle for membership and, for a member, its weight <em>inside this group</em>.</li>
 * </ul>
 * <p>A Test button under the pages stands up a tunnel built from the group alone.</p>
 *
 * <p>Everything is read from the roster's {@link EditorRosterPacket.TunnelGroups} snapshot and every
 * edit is a {@code tunnelgroups} command; the screen dispatches the {@link Click}s this returns.</p>
 */
final class EditorGroupsTab {

    static final String ROOT = "dungeontrain editor tracks tunnelgroups";
    static final String SECTION = "tunnel_section";
    static final String PORTAL = "tunnel_portal";
    private static final String UNGROUPED_TOKEN = "ungrouped";

    private static final int ROW_H = EditorDetailPane.ROW_H;
    private static final int ITEM_ROW_H = 18;
    private static final int ICON = 16;
    private static final int GAP = 3;
    private static final int WARN = 0xFFFFAA33;
    private static final int OK = 0xFF77DD77;
    private static final int ROW_SELECTED = 0x60FFFFFF;
    private static final int MEMBER_BG = 0x2044FF44;

    /** What a click asks the screen to do. */
    sealed interface Click {
        /** Handled here (a selection or page change) — the screen only clicks. */
        record Consumed() implements Click {}
        /** Dispatch a menu entry through the modal host (typing, confirm, run). */
        record Entry(CommandMenuEntry entry) implements Click {}
        /** Open a modal screen. */
        record Open(MenuScreen screen) implements Click {}
        /** A data-sheet cell — the screen's own sheet handling (step, type, run). */
        record Sheet(TemplateDataSheet.Placed placed) implements Click {}
        /** Start turning the preview. */
        record Preview() implements Click {}
    }

    /** The right column's pages, in button order. */
    enum Page { GROUP, ENTRANCES, SECTIONS }

    private enum Kind { GROUP_ROW, NEW_ROW, PAGE, ICON, TOGGLE, WEIGHT, DEC, INC, TEST }

    private record Hit(Kind kind, InventoryEditorLayout.Rect rect, String group, String kindId, String name,
                       Page page) {}

    /** The picked group: {@code ""} is Ungrouped. */
    private String selected;
    private Page page = Page.GROUP;
    private int listScroll;
    private int pageScroll;
    private int listBottom;
    private int pageBottom;
    private final List<Hit> hits = new ArrayList<>();
    private List<TemplateDataSheet.Placed> sheetCells = List.of();
    private InventoryEditorLayout.Rect leftRect;
    private InventoryEditorLayout.Rect bodyRect;
    private final Map<Block, ItemStack> stacks = new HashMap<>();

    // ------------------------------------------------------------------ data

    private static EditorRosterPacket.TunnelGroups groups() {
        return EditorRosterClient.tunnelGroups();
    }

    /** Every group token in list order: Ungrouped first, then the named groups. */
    private static List<String> tokens() {
        List<String> out = new ArrayList<>();
        out.add("");
        out.addAll(groups().ids());
        return out;
    }

    private static int weightOf(String group) {
        return group.isEmpty() ? groups().ungroupedWeight() : groups().weights().getOrDefault(group, 1);
    }

    private static List<EditorRosterPacket.TunnelGroups.Member> members(String group, String kindId) {
        return groups().membersOf(group, kindId);
    }

    private static EditorRosterPacket.TunnelGroups.Member memberOf(String group, String kindId, String name) {
        for (EditorRosterPacket.TunnelGroups.Member m : members(group, kindId)) {
            if (m.name().equals(name)) return m;
        }
        return null;
    }

    private static String label(String group) {
        return group.isEmpty() ? "Ungrouped" : group;
    }

    /** Why the group cannot build a tunnel, or null when it can. */
    private static String missing(String group) {
        boolean sections = !members(group, SECTION).isEmpty();
        boolean portals = !members(group, PORTAL).isEmpty();
        if (!sections && !portals) return "empty — add a section and an entrance";
        if (!sections) return "needs a tunnel section";
        if (!portals) return "needs an entrance";
        return null;
    }

    private void reconcile() {
        List<String> tokens = tokens();
        if (selected == null || !tokens.contains(selected)) {
            selected = tokens.size() > 1 ? tokens.get(1) : "";
            pageScroll = 0;
        }
    }

    /** Every top-level template of {@code kindId}: id → its roster tile, in id order. */
    private static TreeMap<String, EditorRosterIndex.Tile> templatesOf(String kindId) {
        TreeMap<String, EditorRosterIndex.Tile> out = new TreeMap<>();
        for (EditorRosterIndex.Tile tile : EditorRosterClient.index().allTiles()) {
            if (tile.key() == null || tile.key().isSubVariant()) continue;
            if (kindId.equals(tile.key().modelId())) out.put(tile.variant().name(), tile);
        }
        return out;
    }

    private static EditorRosterIndex.Tile tileOf(String kindId, String name) {
        return templatesOf(kindId).get(name);
    }

    private static String displayOf(String kindId, String name) {
        EditorRosterIndex.Tile tile = tileOf(kindId, name);
        return tile != null ? tile.variant().displayName() : name;
    }

    /** The template's most numerous block as an item, or null while its tile is still baking. */
    private ItemStack iconOf(String kindId, String name) {
        EditorRosterIndex.Tile tile = tileOf(kindId, name);
        VariantKey key = tile != null ? tile.key() : VariantKey.of(PlotCategory.TRACKS, kindId, name);
        TemplateArt art = TemplateArt.of(key);
        TemplateSummary summary = art == null ? null : art.summary();
        Block top = summary == null ? null : summary.topBlock();
        if (top == null) return null;
        ItemStack stack = stacks.computeIfAbsent(top, b -> new ItemStack(b.asItem()));
        return stack.is(Items.AIR) ? null : stack;
    }

    private static String kindTitle(String kindId) {
        return PORTAL.equals(kindId) ? "Entrances" : "Sections";
    }

    private static String kindOf(Page p) {
        return p == Page.ENTRANCES ? PORTAL : SECTION;
    }

    // ------------------------------------------------------------------ render

    void render(GuiGraphics g, Font font, EditorScreenTheme theme, InventoryEditorLayout layout,
                float yaw, int mx, int my) {
        reconcile();
        hits.clear();
        sheetCells = List.of();
        leftRect = EditorSettingsPane.rect(layout);
        renderList(g, font, theme, mx, my);

        InventoryEditorLayout.Rect h = layout.header();
        String head = label(selected) + " · rolls ×" + weightOf(selected);
        g.drawString(font, font.plainSubstrByWidth(head, h.w() - 4), h.x() + 2,
            h.y() + (h.h() - font.lineHeight) / 2, theme.panelText(), !theme.isLight());

        renderPageButtons(g, font, layout.icons(), mx, my);
        InventoryEditorLayout.Rect t = layout.test();
        bodyRect = new InventoryEditorLayout.Rect(h.x(), layout.preview().y(), h.w(),
            Math.max(0, t.y() - GAP - layout.preview().y()));
        g.fill(bodyRect.x(), bodyRect.y(), bodyRect.right(), bodyRect.bottom(), PreviewPane.BACKDROP);
        g.enableScissor(bodyRect.x(), bodyRect.y(), bodyRect.right(), bodyRect.bottom());
        if (page == Page.GROUP) renderGroupPage(g, font, mx, my);
        else renderKindPage(g, font, kindOf(page), mx, my);
        g.disableScissor();
        g.renderOutline(bodyRect.x(), bodyRect.y(), bodyRect.w(), bodyRect.h(), theme.outline());

        renderTest(g, font, t, mx, my);
    }

    private void renderList(GuiGraphics g, Font font, EditorScreenTheme theme, int mx, int my) {
        InventoryEditorLayout.Rect r = leftRect;
        g.enableScissor(r.x(), r.y(), r.right(), r.bottom());
        int y = r.y() + 2 - listScroll;
        for (String token : tokens()) {
            InventoryEditorLayout.Rect row = new InventoryEditorLayout.Rect(r.x() + 2, y, r.w() - 4, ROW_H - 1);
            boolean hov = row.contains(mx, my) && r.contains(mx, my);
            if (token.equals(selected)) g.fill(row.x(), row.y(), row.right(), row.bottom(), ROW_SELECTED);
            else if (hov) g.fill(row.x(), row.y(), row.right(), row.bottom(), MenuRowPainter.CELL_HOVER);
            String right = "×" + weightOf(token) + "   " + members(token, PORTAL).size() + " entrances · "
                + members(token, SECTION).size() + " sections";
            boolean builds = missing(token) == null;
            g.drawString(font, (builds ? "" : "! ") + label(token), row.x() + 3, row.y() + 3,
                builds ? theme.panelText() : WARN, false);
            g.drawString(font, right, row.right() - font.width(right) - 3, row.y() + 3, theme.panelText(), false);
            hits.add(new Hit(Kind.GROUP_ROW, row, token, null, null, null));
            y += ROW_H;
        }
        InventoryEditorLayout.Rect newRow = new InventoryEditorLayout.Rect(r.x() + 2, y, r.w() - 4, ROW_H - 1);
        g.fill(newRow.x(), newRow.y(), newRow.right(), newRow.bottom(),
            newRow.contains(mx, my) ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
        g.drawString(font, "+ New group…", newRow.x() + 3, newRow.y() + 3, OK, false);
        hits.add(new Hit(Kind.NEW_ROW, newRow, null, null, null, null));
        listBottom = y + ROW_H + listScroll;
        g.disableScissor();
    }

    private void renderPageButtons(GuiGraphics g, Font font, InventoryEditorLayout.Rect icons, int mx, int my) {
        int x = icons.x();
        for (Page p : Page.values()) {
            String text = switch (p) {
                case GROUP -> "Group";
                case ENTRANCES -> "Entrances " + members(selected, PORTAL).size();
                case SECTIONS -> "Sections " + members(selected, SECTION).size();
            };
            int w = font.width(text) + 8;
            InventoryEditorLayout.Rect b = new InventoryEditorLayout.Rect(x, icons.y(), w, Math.min(icons.h(), 14));
            boolean on = p == page;
            boolean hov = b.contains(mx, my);
            g.fill(b.x(), b.y(), b.right(), b.bottom(),
                hov ? MenuRowPainter.CELL_HOVER : on ? ROW_SELECTED : MenuRowPainter.CELL_IDLE);
            g.drawString(font, text, b.x() + 4, b.y() + (b.h() - font.lineHeight) / 2 + 1,
                hov ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF, false);
            hits.add(new Hit(Kind.PAGE, b, selected, null, null, p));
            x = b.right() + GAP;
        }
    }

    /** Counts with one icon per member, then the name / weight / builds sheet. */
    private void renderGroupPage(GuiGraphics g, Font font, int mx, int my) {
        InventoryEditorLayout.Rect r = bodyRect;
        int y = r.y() + 4;
        y = renderCountRow(g, font, r, y, PORTAL, Page.ENTRANCES, mx, my);
        y = renderCountRow(g, font, r, y + GAP, SECTION, Page.SECTIONS, mx, my);

        List<TemplateDataSheet.Line> lines = new ArrayList<>();
        if (!selected.isEmpty()) {
            lines.add(new TemplateDataSheet.Line("Name", List.of(
                TemplateDataSheet.Cell.plain(selected),
                // Drawn as buttons (they carry an action); click() answers them before the sheet does.
                new TemplateDataSheet.Cell("Rename", new TemplateDataSheet.Action.Run(""), true),
                new TemplateDataSheet.Cell("Delete", new TemplateDataSheet.Action.Run(""), true))));
        }
        String weightPrefix = selected.isEmpty() ? ROOT + " ungrouped" : ROOT + " weight " + selected;
        lines.add(new TemplateDataSheet.Line("Weight", stepper(weightOf(selected), weightPrefix,
            "How often a tunnel rolls this group, against the other groups.")));
        String why = missing(selected);
        lines.add(TemplateDataSheet.Line.of("Builds", why == null ? "yes" : why));
        InventoryEditorLayout.Rect sheet = new InventoryEditorLayout.Rect(r.x() + 2, y + GAP * 2, r.w() - 4,
            Math.max(0, r.bottom() - y - GAP * 2));
        sheetCells = TemplateDataSheet.place(lines, sheet, font);
        int hovered = -1;
        for (int i = 0; i < sheetCells.size(); i++) {
            if (sheetCells.get(i).rect().contains(mx, my)) hovered = i;
        }
        TemplateDataSheet.draw(g, font, sheet, lines, sheetCells, hovered);
    }

    /** {@code Entrances  2} and a wrapping row of one icon per member; the icons open that page. */
    private int renderCountRow(GuiGraphics g, Font font, InventoryEditorLayout.Rect r, int y, String kindId,
                               Page target, int mx, int my) {
        List<EditorRosterPacket.TunnelGroups.Member> ms = members(selected, kindId);
        String title = kindTitle(kindId) + "  " + ms.size();
        g.drawString(font, title, r.x() + 4, y, TemplateDataSheet.LABEL, false);
        y += font.lineHeight + 2;
        int x = r.x() + 4;
        if (ms.isEmpty()) {
            g.drawString(font, "none yet", x, y + 4, PreviewPane.HINT, false);
            return y + ICON + 2;
        }
        for (EditorRosterPacket.TunnelGroups.Member m : ms) {
            if (x + ICON > r.right() - 4) {
                x = r.x() + 4;
                y += ICON + 2;
            }
            InventoryEditorLayout.Rect cell = new InventoryEditorLayout.Rect(x, y, ICON, ICON);
            if (cell.contains(mx, my)) g.fill(x - 1, y - 1, x + ICON + 1, y + ICON + 1, MenuRowPainter.CELL_HOVER);
            drawIcon(g, font, iconOf(kindId, m.name()), displayOf(kindId, m.name()), x, y);
            hits.add(new Hit(Kind.ICON, cell, selected, kindId, m.name(), target));
            x += ICON + 2;
        }
        return y + ICON + 2;
    }

    private static void drawIcon(GuiGraphics g, Font font, ItemStack icon, String name, int x, int y) {
        if (icon != null) {
            g.renderItem(icon, x, y);
            return;
        }
        // Still baking (or a block with no item): its initial on a slate, as a tile does.
        g.fill(x, y, x + ICON, y + ICON, 0xFF3A3F45);
        String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        g.drawString(font, initial, x + (ICON - font.width(initial)) / 2, y + 4, 0xFFFFFFFF, false);
    }

    /** Every template of the kind — members first — with its toggle and in-group weight. */
    private void renderKindPage(GuiGraphics g, Font font, String kindId, int mx, int my) {
        InventoryEditorLayout.Rect r = bodyRect;
        List<String> ordered = new ArrayList<>();
        TreeMap<String, EditorRosterIndex.Tile> all = templatesOf(kindId);
        for (String name : all.keySet()) if (memberOf(selected, kindId, name) != null) ordered.add(name);
        for (String name : all.keySet()) if (memberOf(selected, kindId, name) == null) ordered.add(name);

        int y = r.y() + 2 - pageScroll;
        for (String name : ordered) {
            EditorRosterPacket.TunnelGroups.Member m = memberOf(selected, kindId, name);
            InventoryEditorLayout.Rect row = new InventoryEditorLayout.Rect(r.x() + 2, y, r.w() - 4, ITEM_ROW_H - 1);
            if (m != null) g.fill(row.x(), row.y(), row.right(), row.bottom(), MEMBER_BG);
            drawIcon(g, font, iconOf(kindId, name), displayOf(kindId, name), row.x() + 1, row.y());

            int right = row.right() - 2;
            int ty = row.y() + (ITEM_ROW_H - font.lineHeight) / 2;
            // The toggle, rightmost. The ungrouped pool's membership is "in no group", so there is
            // nothing to tick there — its rows only carry the template's own weight.
            if (!selected.isEmpty()) {
                right = cell(g, font, m != null ? "[x]" : "[ ]", right, ty, Kind.TOGGLE, kindId, name, mx, my);
            }
            if (m != null) {
                right = cell(g, font, "+", right - GAP, ty, Kind.INC, kindId, name, mx, my);
                right = cell(g, font, "-", right - 1, ty, Kind.DEC, kindId, name, mx, my);
                right = cell(g, font, "×" + m.weight(), right - 1, ty, Kind.WEIGHT, kindId, name, mx, my);
            }
            String shown = font.plainSubstrByWidth(displayOf(kindId, name), Math.max(0, right - row.x() - ICON - 8));
            g.drawString(font, shown, row.x() + ICON + 5, ty, m != null ? 0xFFFFFFFF : 0xB0FFFFFF, false);
            y += ITEM_ROW_H;
        }
        if (ordered.isEmpty()) {
            g.drawString(font, "No " + kindTitle(kindId).toLowerCase(java.util.Locale.ROOT) + " yet.",
                r.x() + 4, r.y() + 4, PreviewPane.HINT, false);
        }
        pageBottom = y + pageScroll;
    }

    /** A small right-aligned button ending at {@code right}; returns its left edge. */
    private int cell(GuiGraphics g, Font font, String text, int right, int ty, Kind kind, String kindId,
                     String name, int mx, int my) {
        int w = font.width(text) + 4;
        InventoryEditorLayout.Rect b = new InventoryEditorLayout.Rect(right - w, ty - 2, w, font.lineHeight + 3);
        boolean hov = b.contains(mx, my) && bodyRect.contains(mx, my);
        g.fill(b.x(), b.y(), b.right(), b.bottom(), hov ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
        g.drawString(font, text, b.x() + 2, ty, hov ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF, false);
        hits.add(new Hit(kind, b, selected, kindId, name, null));
        return b.x();
    }

    private void renderTest(GuiGraphics g, Font font, InventoryEditorLayout.Rect t, int mx, int my) {
        boolean can = missing(selected) == null;
        boolean hov = can && t.contains(mx, my);
        g.fill(t.x(), t.y(), t.right(), t.bottom(),
            !can ? EditorDetailPane.DISABLED : hov ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
        String text = "▶ Test " + label(selected);
        g.drawString(font, font.plainSubstrByWidth(text, t.w() - 6), t.x() + 4,
            t.y() + (t.h() - font.lineHeight) / 2 + 1, can ? 0xFFFFFFFF : 0x80FFFFFF, false);
        if (can) hits.add(new Hit(Kind.TEST, t, selected, null, null, null));
    }

    private static List<TemplateDataSheet.Cell> stepper(int value, String prefix, String tip) {
        TemplateDataSheet.Action.Step step = new TemplateDataSheet.Action.Step(prefix, prefix + " dec", prefix + " inc");
        List<TemplateDataSheet.Cell> cells = new ArrayList<>(3);
        cells.add(new TemplateDataSheet.Cell(Integer.toString(value), step, true).withTooltip(tip));
        cells.add(new TemplateDataSheet.Cell("-", new TemplateDataSheet.Action.Run(prefix + " dec"), true));
        cells.add(new TemplateDataSheet.Cell("+", new TemplateDataSheet.Action.Run(prefix + " inc"), true));
        return cells;
    }

    /** The command prefix for a member's weight: inside a named group, or its own weight when ungrouped. */
    private String weightPrefix(String kindId, String name) {
        return selected.isEmpty()
            ? "dungeontrain editor tracks weight " + kindId + " " + name
            : ROOT + " member " + kindId + " " + name + " " + selected;
    }

    // ------------------------------------------------------------------ input

    /** The click at {@code (mx, my)}, or null when it lands on nothing of this tab. */
    Click click(double mx, double my) {
        for (TemplateDataSheet.Placed p : sheetCells) {
            if (!p.rect().contains(mx, my)) continue;
            // Rename / Delete ride on the Name line, told apart by their text.
            if ("Rename".equals(p.cell().text())) {
                return new Click.Entry(new CommandMenuEntry.TypeArg("Rename group", "name",
                    ROOT + " rename " + selected, "", selected));
            }
            if ("Delete".equals(p.cell().text())) {
                return new Click.Entry(new CommandMenuEntry.DrillIn("Delete",
                    new ConfirmScreen("Delete tunnel group '" + selected + "'? Its templates stay.",
                        ROOT + " delete " + selected)));
            }
            if (p.cell().action() != null) return new Click.Sheet(p);
        }
        for (Hit hit : hits) {
            if (!hit.rect().contains(mx, my)) continue;
            boolean inList = hit.kind() == Kind.GROUP_ROW || hit.kind() == Kind.NEW_ROW;
            if (inList && !leftRect.contains(mx, my)) continue;   // scrolled out of view
            boolean inBody = hit.kind() == Kind.ICON || hit.kind() == Kind.TOGGLE || hit.kind() == Kind.WEIGHT
                || hit.kind() == Kind.DEC || hit.kind() == Kind.INC;
            if (inBody && !bodyRect.contains(mx, my)) continue;
            switch (hit.kind()) {
                case GROUP_ROW -> {
                    if (!hit.group().equals(selected)) pageScroll = 0;
                    selected = hit.group();
                    return new Click.Consumed();
                }
                case NEW_ROW -> {
                    return new Click.Entry(new CommandMenuEntry.TypeArg("New group", "group id", ROOT + " new", "", ""));
                }
                case PAGE, ICON -> {
                    page = hit.page();
                    pageScroll = 0;
                    return new Click.Consumed();
                }
                case TOGGLE -> {
                    return new Click.Entry(new CommandMenuEntry.Stay("Toggle",
                        ROOT + " toggle " + hit.kindId() + " " + hit.name() + " " + selected, false));
                }
                case WEIGHT -> {
                    String prefix = weightPrefix(hit.kindId(), hit.name());
                    if (MenuClickModifiers.cmdDown()) {
                        return new Click.Entry(new CommandMenuEntry.TypeArg("Weight", "weight", prefix, "", ""));
                    }
                    return new Click.Entry(new CommandMenuEntry.Stay("Weight",
                        prefix + (Screen.hasShiftDown() ? " dec" : " inc"), false));
                }
                case DEC -> {
                    return new Click.Entry(new CommandMenuEntry.Stay("-",
                        weightPrefix(hit.kindId(), hit.name()) + " dec", false));
                }
                case INC -> {
                    return new Click.Entry(new CommandMenuEntry.Stay("+",
                        weightPrefix(hit.kindId(), hit.name()) + " inc", false));
                }
                case TEST -> {
                    String token = selected.isEmpty() ? UNGROUPED_TOKEN : selected;
                    return new Click.Entry(new CommandMenuEntry.Run("Test",
                        "dungeontrain editor test tracks tunnelgroup " + token, false));
                }
            }
        }
        return null;
    }

    /** A hover line for the sheet cell, group row, icon or weight under the pointer, or null. */
    String tooltipAt(double mx, double my) {
        for (TemplateDataSheet.Placed p : sheetCells) {
            if (p.rect().contains(mx, my) && p.cell().tooltip() != null) return p.cell().tooltip();
        }
        for (Hit hit : hits) {
            if (!hit.rect().contains(mx, my)) continue;
            switch (hit.kind()) {
                case GROUP_ROW -> {
                    String why = missing(hit.group());
                    return why == null ? null : label(hit.group()) + " can't make a tunnel yet: " + why + ".";
                }
                case ICON -> {
                    EditorRosterPacket.TunnelGroups.Member m = memberOf(selected, hit.kindId(), hit.name());
                    return displayOf(hit.kindId(), hit.name()) + (m == null ? "" : " ×" + m.weight());
                }
                case WEIGHT -> {
                    return selected.isEmpty() ? "This template's weight. Click +1, shift −1, cmd to type."
                        : "Weight inside " + selected + ". Click +1, shift −1, cmd to type.";
                }
                case TOGGLE -> {
                    return memberOf(selected, hit.kindId(), hit.name()) != null
                        ? "Take it out of " + selected : "Add it to " + selected;
                }
                default -> { return null; }
            }
        }
        return null;
    }

    boolean over(double mx, double my) {
        return (leftRect != null && leftRect.contains(mx, my)) || (bodyRect != null && bodyRect.contains(mx, my));
    }

    /** Scroll whichever column is under the pointer. */
    boolean scrollBy(double mx, double my, int dir) {
        if (leftRect != null && leftRect.contains(mx, my)) {
            int max = Math.max(0, listBottom - leftRect.bottom() + 4);
            int next = Math.max(0, Math.min(max, listScroll + dir * ROW_H * 2));
            if (next == listScroll) return false;
            listScroll = next;
            return true;
        }
        if (bodyRect != null && bodyRect.contains(mx, my) && page != Page.GROUP) {
            int max = Math.max(0, pageBottom - bodyRect.bottom() + 4);
            int next = Math.max(0, Math.min(max, pageScroll + dir * ITEM_ROW_H * 2));
            if (next == pageScroll) return false;
            pageScroll = next;
            return true;
        }
        return false;
    }
}
