package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.menu.CommandMenuEntry;
import games.brennan.dungeontrain.client.menu.ConfirmScreen;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.client.menu.MenuScreen;
import games.brennan.dungeontrain.client.menu.TunnelGroupMembersScreen;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import games.brennan.dungeontrain.editor.PlotCategory;
import games.brennan.dungeontrain.net.EditorRosterPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * The Groups tab (Tracks only): every tunnel template group and what is in it.
 *
 * <p>The left column lists the groups — the implicit Ungrouped pool first, then each named group
 * with its roll weight and how many sections / entrances it has, marked when it cannot build a
 * tunnel — and under the list the picked group's entrances and sections as tiles, each with a
 * {@code +} tile that opens {@link TunnelGroupMembersScreen}. The right column is the picked group:
 * its name with Rename / Delete, a preview of the picked member, a sheet with the group's weight and
 * the member's weight <em>inside this group</em>, and a Test button that stands up a tunnel built
 * from the group alone.</p>
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
    private static final int LABEL_H = 12;
    private static final int GAP = 3;
    private static final int WARN = 0xFFFFAA33;
    private static final int OK = 0xFF77DD77;
    private static final int ROW_SELECTED = 0x60FFFFFF;

    /** What a click asks the screen to do. */
    sealed interface Click {
        /** Handled here (a selection change) — the screen only clicks. */
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

    private enum Kind { GROUP_ROW, NEW_ROW, MEMBER, ADD, RENAME, DELETE, TEST }

    private record Hit(Kind kind, InventoryEditorLayout.Rect rect, String group, String kindId, String name) {}

    /** The picked group: {@code ""} is Ungrouped. */
    private String selected;
    private String memberKind;
    private String memberName;
    private int scroll;
    /** Where the left column's content ends, unscrolled — bounds the scroll. */
    private int contentBottom;
    private final List<Hit> hits = new ArrayList<>();
    private List<TemplateDataSheet.Line> sheetLines = List.of();
    private List<TemplateDataSheet.Placed> sheetCells = List.of();
    private InventoryEditorLayout.Rect previewRect;
    private InventoryEditorLayout.Rect leftRect;

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
            memberKind = null;
            memberName = null;
        }
        if (memberName != null) {
            boolean still = false;
            for (EditorRosterPacket.TunnelGroups.Member m : members(selected, memberKind)) {
                if (m.name().equals(memberName)) still = true;
            }
            if (!still) {
                memberKind = null;
                memberName = null;
            }
        }
    }

    private static EditorRosterIndex.Tile tileOf(String kindId, String name) {
        for (EditorRosterIndex.Tile tile : EditorRosterClient.index().allTiles()) {
            if (tile.key() == null || tile.key().isSubVariant()) continue;
            if (kindId.equals(tile.key().modelId()) && name.equals(tile.variant().name())) return tile;
        }
        return null;
    }

    private static VariantKey keyOf(String kindId, String name) {
        EditorRosterIndex.Tile tile = tileOf(kindId, name);
        return tile != null ? tile.key() : VariantKey.of(PlotCategory.TRACKS, kindId, name);
    }

    private static String displayOf(String kindId, String name) {
        EditorRosterIndex.Tile tile = tileOf(kindId, name);
        return tile != null ? tile.variant().displayName() : name;
    }

    // ------------------------------------------------------------------ render

    void render(GuiGraphics g, Font font, EditorScreenTheme theme, InventoryEditorLayout layout,
                float yaw, int mx, int my) {
        reconcile();
        hits.clear();
        leftRect = EditorSettingsPane.rect(layout);
        renderLeft(g, font, theme, yaw, mx, my);
        renderRight(g, font, theme, layout, yaw, mx, my);
    }

    private void renderLeft(GuiGraphics g, Font font, EditorScreenTheme theme, float yaw, int mx, int my) {
        InventoryEditorLayout.Rect r = leftRect;
        g.enableScissor(r.x(), r.y(), r.right(), r.bottom());
        int y = r.y() + 2 - scroll;
        for (String token : tokens()) {
            InventoryEditorLayout.Rect row = new InventoryEditorLayout.Rect(r.x() + 2, y, r.w() - 4, ROW_H - 1);
            boolean hov = row.contains(mx, my) && r.contains(mx, my);
            if (token.equals(selected)) g.fill(row.x(), row.y(), row.right(), row.bottom(), ROW_SELECTED);
            else if (hov) g.fill(row.x(), row.y(), row.right(), row.bottom(), MenuRowPainter.CELL_HOVER);
            String right = "×" + weightOf(token) + "  " + members(token, SECTION).size() + " sections · "
                + members(token, PORTAL).size() + " entrances";
            boolean builds = missing(token) == null;
            g.drawString(font, (builds ? "" : "! ") + label(token), row.x() + 3, row.y() + 3,
                builds ? theme.panelText() : WARN, false);
            g.drawString(font, right, row.right() - font.width(right) - 3, row.y() + 3, theme.panelText(), false);
            hits.add(new Hit(Kind.GROUP_ROW, row, token, null, null));
            y += ROW_H;
        }
        InventoryEditorLayout.Rect newRow = new InventoryEditorLayout.Rect(r.x() + 2, y, r.w() - 4, ROW_H - 1);
        g.fill(newRow.x(), newRow.y(), newRow.right(), newRow.bottom(),
            newRow.contains(mx, my) ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
        g.drawString(font, "+ New group…", newRow.x() + 3, newRow.y() + 3, OK, false);
        hits.add(new Hit(Kind.NEW_ROW, newRow, null, null, null));
        y += ROW_H + GAP;

        y = drawMembers(g, font, theme, r, y, PORTAL, "Entrances", yaw, mx, my);
        y = drawMembers(g, font, theme, r, y + GAP, SECTION, "Sections", yaw, mx, my);
        contentBottom = y + scroll;
        g.disableScissor();
    }

    private int drawMembers(GuiGraphics g, Font font, EditorScreenTheme theme, InventoryEditorLayout.Rect r,
                            int y, String kindId, String title, float yaw, int mx, int my) {
        g.drawString(font, title + " in " + label(selected), r.x() + 3, y + 2, theme.panelText(), false);
        y += LABEL_H;
        int size = Math.max(24, Math.min(40, (r.w() - 4) / 5 - GAP));
        int x = r.x() + 2;
        List<EditorRosterPacket.TunnelGroups.Member> ms = members(selected, kindId);
        int count = ms.size() + (selected.isEmpty() ? 0 : 1);
        for (int i = 0; i < count; i++) {
            if (x + size > r.right() - 2) {
                x = r.x() + 2;
                y += size + GAP;
            }
            InventoryEditorLayout.Rect tile = new InventoryEditorLayout.Rect(x, y, size, size);
            boolean hov = tile.contains(mx, my) && r.contains(mx, my);
            if (i < ms.size()) {
                EditorRosterPacket.TunnelGroups.Member m = ms.get(i);
                boolean sel = kindId.equals(memberKind) && m.name().equals(memberName);
                TemplateTilePainter.draw(g, font, TemplateArt.of(keyOf(kindId, m.name())),
                    displayOf(kindId, m.name()), m.weight(), x, y, size, yaw,
                    new TemplateTilePainter.Marks(sel, hov, false, false, false));
                hits.add(new Hit(Kind.MEMBER, tile, selected, kindId, m.name()));
            } else {
                TemplateTilePainter.drawNew(g, font, x, y, size, hov);
                hits.add(new Hit(Kind.ADD, tile, selected, kindId, null));
            }
            x += size + GAP;
        }
        return y + (count == 0 ? 0 : size) + GAP;
    }

    private void renderRight(GuiGraphics g, Font font, EditorScreenTheme theme, InventoryEditorLayout layout,
                             float yaw, int mx, int my) {
        InventoryEditorLayout.Rect h = layout.header();
        String head = label(selected) + " · rolls ×" + weightOf(selected);
        g.drawString(font, font.plainSubstrByWidth(head, h.w() - 4), h.x() + 2,
            h.y() + (h.h() - font.lineHeight) / 2, theme.panelText(), !theme.isLight());

        // Rename / Delete — named groups only; the Ungrouped pool is not a group to rename.
        InventoryEditorLayout.Rect icons = layout.icons();
        if (!selected.isEmpty()) {
            int x = button(g, font, "Rename", icons.x(), icons, Kind.RENAME, mx, my, false);
            button(g, font, "Delete", x, icons, Kind.DELETE, mx, my, true);
        }

        previewRect = layout.preview();
        drawPreview(g, font, theme, previewRect, yaw);

        InventoryEditorLayout.Rect sheetRect = layout.sheet();
        sheetLines = sheetLines();
        sheetCells = TemplateDataSheet.place(sheetLines, sheetRect, font);
        int hovered = -1;
        for (int i = 0; i < sheetCells.size(); i++) {
            if (sheetCells.get(i).rect().contains(mx, my)) hovered = i;
        }
        TemplateDataSheet.draw(g, font, sheetRect, sheetLines, sheetCells, hovered);

        InventoryEditorLayout.Rect t = layout.test();
        boolean can = missing(selected) == null;
        boolean hov = can && t.contains(mx, my);
        g.fill(t.x(), t.y(), t.right(), t.bottom(),
            !can ? EditorDetailPane.DISABLED : hov ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
        String label = "▶ Test " + label(selected);
        g.drawString(font, font.plainSubstrByWidth(label, t.w() - 6), t.x() + 4,
            t.y() + (t.h() - font.lineHeight) / 2 + 1, can ? 0xFFFFFFFF : 0x80FFFFFF, false);
        if (can) hits.add(new Hit(Kind.TEST, t, selected, null, null));
    }

    private int button(GuiGraphics g, Font font, String text, int x, InventoryEditorLayout.Rect icons,
                       Kind kind, int mx, int my, boolean danger) {
        int w = font.width(text) + 8;
        InventoryEditorLayout.Rect b = new InventoryEditorLayout.Rect(x, icons.y(), w, Math.min(icons.h(), 14));
        boolean hov = b.contains(mx, my);
        g.fill(b.x(), b.y(), b.right(), b.bottom(),
            hov ? (danger ? 0xC0FF5544 : MenuRowPainter.CELL_HOVER) : MenuRowPainter.CELL_IDLE);
        g.drawString(font, text, b.x() + 4, b.y() + (b.h() - font.lineHeight) / 2 + 1,
            hov ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF, false);
        hits.add(new Hit(kind, b, selected, null, null));
        return b.right() + GAP;
    }

    private void drawPreview(GuiGraphics g, Font font, EditorScreenTheme theme, InventoryEditorLayout.Rect r,
                             float yaw) {
        g.fill(r.x(), r.y(), r.right(), r.bottom(), PreviewPane.BACKDROP);
        boolean drawn = false;
        if (memberName != null) {
            TemplateArt art = TemplateArt.of(keyOf(memberKind, memberName));
            drawn = art != null && art.drawModel(g, r.x(), r.y(), r.w(), r.h(), yaw, PreviewPane.FILL);
            g.drawString(font, font.plainSubstrByWidth(displayOf(memberKind, memberName), r.w() - 6),
                r.x() + 3, r.y() + 2, PreviewPane.CAPTION, true);
        }
        if (!drawn) {
            String hint = memberName == null ? "Pick a section or entrance" : "…";
            g.drawString(font, hint, r.x() + (r.w() - font.width(hint)) / 2,
                r.y() + (r.h() - font.lineHeight) / 2, PreviewPane.HINT, false);
        }
        g.renderOutline(r.x(), r.y(), r.w(), r.h(), theme.outline());
    }

    /** Weight, whether it builds, and — with a member picked — its weight inside this group. */
    private List<TemplateDataSheet.Line> sheetLines() {
        List<TemplateDataSheet.Line> out = new ArrayList<>();
        String weightPrefix = selected.isEmpty() ? ROOT + " ungrouped" : ROOT + " weight " + selected;
        out.add(new TemplateDataSheet.Line("Weight", stepper(weightOf(selected), weightPrefix,
            "How often a tunnel rolls this group, against the other groups.")));
        String why = missing(selected);
        out.add(TemplateDataSheet.Line.of("Builds", why == null ? "yes" : why));
        if (memberName != null) {
            out.add(TemplateDataSheet.Line.of("Member", displayOf(memberKind, memberName)));
            int w = 1;
            for (EditorRosterPacket.TunnelGroups.Member m : members(selected, memberKind)) {
                if (m.name().equals(memberName)) w = m.weight();
            }
            // Inside a named group the member has its own weight there; an ungrouped template's
            // weight is its template weight.
            String prefix = selected.isEmpty()
                ? "dungeontrain editor tracks weight " + memberKind + " " + memberName
                : ROOT + " member " + memberKind + " " + memberName + " " + selected;
            List<TemplateDataSheet.Cell> cells = new ArrayList<>(stepper(w, prefix,
                selected.isEmpty() ? "This template's weight." : "This template's weight inside " + selected + "."));
            if (!selected.isEmpty()) {
                cells.add(TemplateDataSheet.Cell.plain("·"));
                cells.add(new TemplateDataSheet.Cell("Remove", new TemplateDataSheet.Action.Run(
                    ROOT + " toggle " + memberKind + " " + memberName + " " + selected), true)
                    .withTooltip("Take it out of " + selected + "."));
            }
            out.add(new TemplateDataSheet.Line("In group", cells));
        }
        return out;
    }

    private static List<TemplateDataSheet.Cell> stepper(int value, String prefix, String tip) {
        TemplateDataSheet.Action.Step step = new TemplateDataSheet.Action.Step(prefix, prefix + " dec", prefix + " inc");
        List<TemplateDataSheet.Cell> cells = new ArrayList<>(3);
        cells.add(new TemplateDataSheet.Cell(Integer.toString(value), step, true).withTooltip(tip));
        cells.add(new TemplateDataSheet.Cell("-", new TemplateDataSheet.Action.Run(prefix + " dec"), true));
        cells.add(new TemplateDataSheet.Cell("+", new TemplateDataSheet.Action.Run(prefix + " inc"), true));
        return cells;
    }

    // ------------------------------------------------------------------ input

    /** The click at {@code (mx, my)}, or null when it lands on nothing of this tab. */
    Click click(double mx, double my) {
        for (TemplateDataSheet.Placed p : sheetCells) {
            if (p.cell().action() != null && p.rect().contains(mx, my)) return new Click.Sheet(p);
        }
        for (Hit hit : hits) {
            if (!hit.rect().contains(mx, my)) continue;
            boolean inLeft = hit.kind() == Kind.GROUP_ROW || hit.kind() == Kind.NEW_ROW
                || hit.kind() == Kind.MEMBER || hit.kind() == Kind.ADD;
            if (inLeft && !leftRect.contains(mx, my)) continue;   // scrolled out of view
            switch (hit.kind()) {
                case GROUP_ROW -> {
                    selected = hit.group();
                    memberKind = null;
                    memberName = null;
                    return new Click.Consumed();
                }
                case NEW_ROW -> {
                    return new Click.Entry(new CommandMenuEntry.TypeArg("New group", "group id", ROOT + " new", "", ""));
                }
                case MEMBER -> {
                    memberKind = hit.kindId();
                    memberName = hit.name();
                    return new Click.Consumed();
                }
                case ADD -> {
                    return new Click.Open(new TunnelGroupMembersScreen(hit.group(), hit.kindId()));
                }
                case RENAME -> {
                    return new Click.Entry(new CommandMenuEntry.TypeArg("Rename group", "name",
                        ROOT + " rename " + selected, "", selected));
                }
                case DELETE -> {
                    return new Click.Entry(new CommandMenuEntry.DrillIn("Delete",
                        new ConfirmScreen("Delete tunnel group '" + selected + "'? Its templates stay.",
                            ROOT + " delete " + selected)));
                }
                case TEST -> {
                    String token = selected.isEmpty() ? UNGROUPED_TOKEN : selected;
                    return new Click.Entry(new CommandMenuEntry.Run("Test",
                        "dungeontrain editor test tracks tunnelgroup " + token, false));
                }
            }
        }
        if (previewRect != null && previewRect.contains(mx, my)) return new Click.Preview();
        return null;
    }

    /** A hover line for the sheet cell, group row or tile under the pointer, or null. */
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
                case MEMBER -> {
                    return displayOf(hit.kindId(), hit.name()) + " — click to pick";
                }
                case ADD -> {
                    return "Add or remove " + (PORTAL.equals(hit.kindId()) ? "entrances" : "sections");
                }
                default -> { return null; }
            }
        }
        return null;
    }

    boolean over(double mx, double my) {
        return leftRect != null && leftRect.contains(mx, my);
    }

    boolean scrollBy(int dir) {
        if (leftRect == null) return false;
        int max = Math.max(0, contentBottom - leftRect.bottom() + 4);
        int next = Math.max(0, Math.min(max, scroll + dir * ROW_H * 2));
        if (next == scroll) return false;
        scroll = next;
        return true;
    }
}
