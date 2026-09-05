package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.builder.relay.BuilderReviewState;
import games.brennan.dungeontrain.client.builder.RelayBuildPreviews;
import games.brennan.dungeontrain.client.builder.TemplateSummary;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import games.brennan.dungeontrain.config.EditorScreenTheme;
import games.brennan.dungeontrain.net.BuilderProfilePacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * The right pane while the browser is showing somebody else's uploads.
 *
 * <p>The same shape as {@link EditorDetailPane} — a name, a picture, a short sheet — with two
 * controls rather than its toolbar, because two is all that applies to a row on the relay: bring the
 * build down, and once it is down, go and stand in it. Everything else here is read.</p>
 */
public final class EditorCreatorPane {

    static final int LINE_H = 10;
    static final int LABEL_W = 52;
    static final int LOADED_TEXT = 0xFF88DD88;

    /** What a click landed on. */
    public enum HitKind { NONE, LOAD, GO_HERE, PREVIEW }

    private InventoryEditorLayout.Rect loadRect;
    private InventoryEditorLayout.Rect goHereRect;
    private InventoryEditorLayout.Rect previewRect;

    public void render(GuiGraphics g, Font font, InventoryEditorLayout layout,
                       EditorScreenTheme theme, BuilderProfilePacket.Entry entry, float yaw,
                       String note, boolean asCopy, EditorCreatorBuilds.Landed landed,
                       int mouseX, int mouseY) {

        drawHeader(g, font, layout.header(), theme, entry, landed, mouseX, mouseY);

        previewRect = layout.preview();
        TemplateArt art = entry == null ? null : EditorCreatorBuilds.artOf(entry);
        PreviewPane.draw(g, font, previewRect, art, entry == null ? "" : EditorCreatorBuilds.label(entry),
            yaw, theme, entry == null ? 0 : entry.relayId());
        // The review colour rings the picture rather than sitting in the sheet as a fourth word:
        // it is the one fact a reviewer scans for, and My Builds already teaches the colours.
        if (entry != null) {
            int border = BuilderReviewState.borderColourFor(entry.review());
            if (border != BuilderReviewState.BORDER_NONE) {
                g.renderOutline(previewRect.x(), previewRect.y(), previewRect.w(), previewRect.h(), border);
            }
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
        InventoryEditorLayout.Rect notes = layout.settings();
        g.drawString(font, font.plainSubstrByWidth(
                EditorScreenLang.text(EditorScreenLang.CREATOR_READ_ONLY), notes.w() - 4),
            notes.x() + 2, notes.y(), EditorDetailPane.DIM_TEXT, false);
        if (note != null && !note.isEmpty()) {
            // Wrapped, not clipped: these say what happened and why, and half a sentence
            // ("You already have a build by t") is worse than no sentence.
            int y2 = notes.y() + LINE_H + 2;
            for (FormattedCharSequence row : font.split(Component.literal(note), notes.w() - 4)) {
                if (y2 + LINE_H > notes.bottom()) break;
                g.drawString(font, row, notes.x() + 2, y2, 0xFFFFEEBB, false);
                y2 += LINE_H;
            }
        }

        drawLoad(g, font, layout.test(), entry, landed, asCopy, mouseX, mouseY);
    }

    /**
     * The header: what the build is called, and — once it is here — the way to go and stand in it.
     *
     * <p>The button appears where a template's own "you are here" line does, because it answers the
     * same question in the same place: the build is somewhere, and this is how you get to it.</p>
     */
    private void drawHeader(GuiGraphics g, Font font, InventoryEditorLayout.Rect h, EditorScreenTheme theme,
                            BuilderProfilePacket.Entry entry, EditorCreatorBuilds.Landed landed,
                            int mouseX, int mouseY) {
        int ty = h.y() + (h.h() - font.lineHeight) / 2;
        String title = entry == null
            ? EditorScreenLang.text(EditorScreenLang.CREATOR_NOTHING_SELECTED)
            : EditorCreatorBuilds.label(entry);

        goHereRect = null;
        // Only a build the editor has a home for: a carriage group is authored in the Train Builder
        // and there is nowhere in here to send anybody.
        boolean canGo = landed != null
            && EditorTemplateJumpBridge.hasHome(landed.kind(), landed.subKind());
        int nameWidth = h.w() - 4;
        if (canGo) {
            String label = EditorScreenLang.text(EditorScreenLang.GO_HERE);
            int w = font.width(label) + 8;
            goHereRect = new InventoryEditorLayout.Rect(h.right() - w - 1, h.y() + 1, w, h.h() - 2);
            nameWidth = Math.max(0, goHereRect.x() - h.x() - 6);
            boolean hot = goHereRect.contains(mouseX, mouseY);
            g.fill(goHereRect.x(), goHereRect.y(), goHereRect.right(), goHereRect.bottom(),
                hot ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
            g.drawString(font, label, goHereRect.x() + 4, ty,
                hot ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF, false);
        }
        g.drawString(font, font.plainSubstrByWidth(title, nameWidth), h.x() + 2, ty,
            theme.panelText(), !theme.isLight());
    }

    /**
     * <b>Load into editor</b>, where a template has <b>Test the Carriage</b> — until it has been
     * loaded, after which the slot says so instead of offering it again.
     *
     * <p>Loading writes the build into this install's library, after which it is an ordinary
     * template: it appears in the roster, and the header's <b>Go here</b> walks the player to it. A
     * name already taken here turns the button into the copy, because that is the only answer left
     * that does not overwrite somebody's work.</p>
     */
    private void drawLoad(GuiGraphics g, Font font, InventoryEditorLayout.Rect r,
                          BuilderProfilePacket.Entry entry, EditorCreatorBuilds.Landed landed,
                          boolean asCopy, int mouseX, int mouseY) {
        loadRect = null;
        if (landed != null) {
            // Done, and not a button: pressing it again would fetch the same build and be told the
            // name is taken — by the copy it just made.
            String done = EditorScreenLang.text(EditorScreenLang.CREATOR_LOADED);
            g.drawString(font, font.plainSubstrByWidth(done, r.w() - 4),
                r.x() + (r.w() - font.width(done)) / 2, r.y() + (r.h() - font.lineHeight) / 2 + 1,
                LOADED_TEXT, false);
            return;
        }
        boolean enabled = entry != null;
        loadRect = enabled ? r : null;
        boolean hot = enabled && r.contains(mouseX, mouseY);
        g.fill(r.x(), r.y(), r.right(), r.bottom(), !enabled ? EditorDetailPane.DISABLED
            : hot ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
        String label = EditorScreenLang.text(asCopy
            ? EditorScreenLang.CREATOR_LOAD_COPY : EditorScreenLang.CREATOR_LOAD);
        g.drawString(font, font.plainSubstrByWidth(label, r.w() - 6),
            r.x() + (r.w() - font.width(label)) / 2, r.y() + (r.h() - font.lineHeight) / 2 + 1,
            !enabled ? 0x80FFFFFF : hot ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF, false);
    }

    /** What a click at this point means. Reads back the geometry of the last frame. */
    public HitKind hitTest(double mx, double my) {
        if (goHereRect != null && goHereRect.contains(mx, my)) return HitKind.GO_HERE;
        if (loadRect != null && loadRect.contains(mx, my)) return HitKind.LOAD;
        if (previewRect != null && previewRect.contains(mx, my)) return HitKind.PREVIEW;
        return HitKind.NONE;
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
