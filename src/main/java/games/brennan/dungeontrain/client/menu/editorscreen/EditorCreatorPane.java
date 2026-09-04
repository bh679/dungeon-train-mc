package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.client.builder.RelayBuildPreviews;
import games.brennan.dungeontrain.client.builder.TemplateSummary;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * The right pane while the browser is showing somebody else's uploads.
 *
 * <p>The same shape as {@link EditorDetailPane} — a name, a picture, a short sheet — with none of
 * its controls, because none of them apply: a relay row cannot be saved, renamed, stood in or
 * tested. What it can be is read, so this says who made it, what kind it is, and where it stands
 * with a reviewer.</p>
 */
public final class EditorCreatorPane {

    static final int LINE_H = 10;
    static final int LABEL_W = 52;

    private EditorCreatorPane() {}

    public static void render(GuiGraphics g, Font font, InventoryEditorLayout layout,
                              EditorScreenTheme theme, BuilderProfilePacket.Entry entry, float yaw,
                              String note, boolean asCopy, boolean loadHovered) {
        InventoryEditorLayout.Rect h = layout.header();
        int ty = h.y() + (h.h() - font.lineHeight) / 2;
        String title = entry == null
            ? EditorScreenLang.text(EditorScreenLang.CREATOR_NOTHING_SELECTED)
            : EditorCreatorBuilds.label(entry);
        g.drawString(font, font.plainSubstrByWidth(title, h.w() - 4), h.x() + 2, ty,
            theme.panelText(), !theme.isLight());

        InventoryEditorLayout.Rect p = layout.preview();
        TemplateArt art = entry == null ? null : EditorCreatorBuilds.artOf(entry);
        PreviewPane.draw(g, font, p, art, entry == null ? "" : EditorCreatorBuilds.label(entry), yaw, theme,
            entry == null ? 0 : entry.relayId());
        // The review colour rings the picture rather than sitting in the sheet as a fourth word:
        // it is the one fact a reviewer scans for, and My Builds already teaches the colours.
        if (entry != null) {
            int border = BuilderReviewState.borderColourFor(entry.review());
            if (border != BuilderReviewState.BORDER_NONE) g.renderOutline(p.x(), p.y(), p.w(), p.h(), border);
        }

        InventoryEditorLayout.Rect s = layout.sheet();
        int y = s.y();
        for (String[] line : lines(entry)) {
            if (y + LINE_H > s.bottom()) break;
            g.drawString(font, line[0], s.x() + 2, y, MenuRowPainter.TEXT_HEADER, false);
            g.drawString(font, font.plainSubstrByWidth(line[1], s.w() - LABEL_W - 6),
                s.x() + LABEL_W, y, 0xFFFFFFFF, false);
            y += LINE_H;
        }

        // Why the toolbar is missing, said once rather than as eight disabled buttons — and, under
        // it, whatever the last press of Load came back with.
        InventoryEditorLayout.Rect lines = layout.settings();
        g.drawString(font, font.plainSubstrByWidth(
                EditorScreenLang.text(EditorScreenLang.CREATOR_READ_ONLY), lines.w() - 4),
            lines.x() + 2, lines.y(), EditorDetailPane.DIM_TEXT, false);
        if (note != null && !note.isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(note, lines.w() - 4),
                lines.x() + 2, lines.y() + LINE_H + 2, 0xFFFFEEBB, false);
        }

        drawLoad(g, font, layout.test(), entry != null, asCopy, loadHovered);
    }

    /**
     * <b>Load into editor</b>, where a template has <b>Test the Carriage</b>.
     *
     * <p>The one thing this pane does rather than reports, and the point of the whole detour: a
     * build that is only a picture cannot be walked through. Loading writes it into this install's
     * library, after which it is an ordinary template — it appears in the roster, can be stood in
     * and tested like anything else. Once a name here is already taken the button offers the copy
     * instead, because that is the only answer left that does not overwrite somebody's work.</p>
     */
    static void drawLoad(GuiGraphics g, Font font, InventoryEditorLayout.Rect r, boolean enabled,
                         boolean asCopy, boolean hovered) {
        boolean hot = enabled && hovered;
        g.fill(r.x(), r.y(), r.right(), r.bottom(), !enabled ? EditorDetailPane.DISABLED
            : hot ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
        String label = EditorScreenLang.text(asCopy
            ? EditorScreenLang.CREATOR_LOAD_COPY : EditorScreenLang.CREATOR_LOAD);
        g.drawString(font, font.plainSubstrByWidth(label, r.w() - 6),
            r.x() + (r.w() - font.width(label)) / 2, r.y() + (r.h() - font.lineHeight) / 2 + 1,
            !enabled ? 0x80FFFFFF : hot ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF, false);
    }

    /** The sheet: who made it, what it is, and what has happened to it. */
    static List<String[]> lines(BuilderProfilePacket.Entry entry) {
        List<String[]> out = new ArrayList<>();
        if (entry == null) return out;
        out.add(new String[] {EditorScreenLang.text(EditorScreenLang.CREATOR_BY),
            entry.ownerName().isEmpty() ? EditorCreatorBuilds.viewedName() : entry.ownerName()});
        out.add(new String[] {EditorScreenLang.text(EditorScreenLang.CREATOR_KIND),
            EditorScreenLang.text(EditorCreatorBuilds.kindKey(entry.kind()))});
        if (!entry.stage().isEmpty()) {
            out.add(new String[] {EditorScreenLang.text(EditorScreenLang.SHEET_STAGE), entry.stage()});
        }
        // The numbers come from the blocks the relay sent for the picture, so they appear with it
        // rather than being asked for separately.
        TemplateSummary summary = RelayBuildPreviews.summary(entry.relayId());
        if (summary != null) {
            out.add(new String[] {EditorScreenLang.text(EditorScreenLang.SHEET_SIZE),
                summary.declaredSize().getX() + " × " + summary.declaredSize().getY()
                    + " × " + summary.declaredSize().getZ()});
            out.add(new String[] {EditorScreenLang.text(EditorScreenLang.SHEET_BLOCKS),
                Integer.toString(summary.blocks())});
        }
        out.add(new String[] {EditorScreenLang.text(EditorScreenLang.CREATOR_CHANGES),
            Integer.toString(entry.changes())});
        out.add(new String[] {EditorScreenLang.text(EditorScreenLang.CREATOR_STATUS),
            EditorScreenLang.text(EditorCreatorBuilds.reviewKey(entry.review()))});
        return out;
    }
}
