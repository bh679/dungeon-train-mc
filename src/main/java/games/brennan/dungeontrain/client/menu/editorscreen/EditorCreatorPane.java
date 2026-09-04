package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
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
                              EditorScreenTheme theme, BuilderProfilePacket.Entry entry, float yaw) {
        InventoryEditorLayout.Rect h = layout.header();
        int ty = h.y() + (h.h() - font.lineHeight) / 2;
        String title = entry == null
            ? EditorScreenLang.text(EditorScreenLang.CREATOR_NOTHING_SELECTED)
            : EditorCreatorBuilds.label(entry);
        g.drawString(font, font.plainSubstrByWidth(title, h.w() - 4), h.x() + 2, ty,
            theme.panelText(), !theme.isLight());

        InventoryEditorLayout.Rect p = layout.preview();
        TemplateArt art = entry == null ? null : EditorCreatorBuilds.artOf(entry);
        PreviewPane.draw(g, font, p, art, entry == null ? "" : EditorCreatorBuilds.label(entry), yaw, theme);
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

        // Why the toolbar is missing, said once rather than as eight disabled buttons.
        InventoryEditorLayout.Rect note = layout.settings();
        g.drawString(font, font.plainSubstrByWidth(
                EditorScreenLang.text(EditorScreenLang.CREATOR_READ_ONLY), note.w() - 4),
            note.x() + 2, note.y(), EditorDetailPane.DIM_TEXT, false);
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
        out.add(new String[] {EditorScreenLang.text(EditorScreenLang.CREATOR_CHANGES),
            Integer.toString(entry.changes())});
        out.add(new String[] {EditorScreenLang.text(EditorScreenLang.CREATOR_STATUS),
            EditorScreenLang.text(EditorCreatorBuilds.reviewKey(entry.review()))});
        return out;
    }
}
