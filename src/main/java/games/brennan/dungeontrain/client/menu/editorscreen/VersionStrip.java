package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.builder.RelayBuildPreviews;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * ◀ v3 of 7 ▶ in the corner of a preview — paging through the versions the relay recorded of a
 * build.
 *
 * <p>Drawn only once the relay has said what it holds ({@link RelayBuildPreviews#versions}) and
 * holds something: a build the relay never saw, or one with a single recorded frame, shows no
 * strip rather than a pair of arrows that go nowhere. "Current" is the build as it is now, which
 * sits one past the newest recorded frame — the arrows walk back into the history and forward out
 * of it again.</p>
 */
public final class VersionStrip {

    static final int ARROW_W = 12;
    static final int H = 12;
    static final int PAD = 2;

    /** What a click landed on. */
    public enum Hit { NONE, OLDER, NEWER, LOAD }

    private InventoryEditorLayout.Rect older;
    private InventoryEditorLayout.Rect newer;
    private InventoryEditorLayout.Rect load;

    /** Draw the strip in the top-right of {@code preview}, if this build has versions to page. */
    public void draw(GuiGraphics g, Font font, InventoryEditorLayout.Rect preview, int relayId, int seq,
                     int mouseX, int mouseY) {
        draw(g, font, preview, relayId, seq, false, mouseX, mouseY);
    }

    /**
     * As above, with a <b>Load vN</b> button under the strip while an older version is showing —
     * for the pane that has no load slot of its own (a template's own detail). Loading replaces the
     * template with that version; the relay's current one is untouched until the next save.
     */
    public void draw(GuiGraphics g, Font font, InventoryEditorLayout.Rect preview, int relayId, int seq,
                     boolean offerLoad, int mouseX, int mouseY) {
        older = null;
        newer = null;
        load = null;
        int[] seqs = relayId > 0 ? RelayBuildPreviews.versions(relayId) : null;
        if (seqs == null || seqs.length < 1) return;

        int at = indexOf(seqs, seq);   // seqs.length means "current"
        String label = seq == 0
            ? EditorScreenLang.text(EditorScreenLang.VERSION_CURRENT)
            : EditorScreenLang.text(EditorScreenLang.VERSION, at + 1, seqs.length);
        String caption = seq == 0 ? null : caption(RelayBuildPreviews.versionInfo(relayId), seqs, seq);
        int labelW = font.width(label) + PAD * 2;
        int w = ARROW_W + labelW + ARROW_W;
        int x = preview.right() - w - 2;
        int y = preview.y() + 2;
        boolean canOlder = at > 0;
        boolean canNewer = seq != 0;
        older = canOlder ? new InventoryEditorLayout.Rect(x, y, ARROW_W, H) : null;
        newer = canNewer ? new InventoryEditorLayout.Rect(x + ARROW_W + labelW, y, ARROW_W, H) : null;

        g.fill(x, y, x + w, y + H, 0xC0101418);
        drawArrow(g, font, "\u25C0", x, y, canOlder, older != null && older.contains(mouseX, mouseY));
        g.drawString(font, label, x + ARROW_W + PAD, y + (H - font.lineHeight) / 2 + 1, 0xFFFFEEBB, false);
        drawArrow(g, font, "\u25B6", x + ARROW_W + labelW, y, canNewer, newer != null && newer.contains(mouseX, mouseY));
        if (caption != null && !caption.isEmpty()) {
            // Under the strip, right-aligned with it: where this version came from. A branch —
            // a save made from something other than the version before it — reads the same as a
            // straight line here; the number says which, and that is what a player needs to know
            // before loading it.
            String shown = font.plainSubstrByWidth(caption, preview.w() - 8);
            int cw = font.width(shown) + PAD * 2;
            int cx = preview.right() - cw - 2;
            int cy = y + H + 1;
            g.fill(cx, cy, cx + cw, cy + H, 0xC0101418);
            g.drawString(font, shown, cx + PAD, cy + (H - font.lineHeight) / 2 + 1, 0xFFC8C8C8, false);
        }
        if (offerLoad && seq != 0) {
            String text = EditorScreenLang.text(EditorScreenLang.VERSION_LOAD, at + 1);
            int lw = font.width(text) + PAD * 4;
            int lx = preview.right() - lw - 2;
            int ly = y + H + 1 + (caption != null && !caption.isEmpty() ? H + 1 : 0);
            load = new InventoryEditorLayout.Rect(lx, ly, lw, H);
            boolean hot = load.contains(mouseX, mouseY);
            g.fill(lx, ly, lx + lw, ly + H, hot ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
            g.drawString(font, text, lx + PAD * 2, ly + (H - font.lineHeight) / 2 + 1,
                hot ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF, false);
        }
    }

    /**
     * What to say under an older version: "from vN" for the version it was saved from, with "by
     * <name>" when the relay said who — or only the name, when the save is a first version or a
     * relay that keeps no parents. Empty when there is nothing to say.
     */
    static String caption(RelayBuildPreviews.VersionInfo info, int[] seqs, int seq) {
        if (info == null || seq == 0) return "";
        int parent = info.parentOf(seq);
        int parentAt = parent > 0 ? indexOf(seqs, parent) : seqs.length;
        String author = info.authorOf(seq);
        String from = parentAt < seqs.length ? EditorScreenLang.text(EditorScreenLang.VERSION_FROM, parentAt + 1) : "";
        String by = author.isEmpty() ? "" : EditorScreenLang.text(EditorScreenLang.VERSION_BY, author);
        if (from.isEmpty()) return by;
        return by.isEmpty() ? from : from + " \u00B7 " + by;
    }

    private static void drawArrow(GuiGraphics g, Font font, String glyph, int x, int y, boolean enabled, boolean hot) {
        g.fill(x, y, x + ARROW_W, y + H, !enabled ? 0x30FFFFFF : hot ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
        g.drawString(font, glyph, x + (ARROW_W - font.width(glyph)) / 2, y + (H - font.lineHeight) / 2 + 1,
            !enabled ? 0x60FFFFFF : hot ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF, false);
    }

    public Hit hit(double mx, double my) {
        if (older != null && older.contains(mx, my)) return Hit.OLDER;
        if (newer != null && newer.contains(mx, my)) return Hit.NEWER;
        if (load != null && load.contains(mx, my)) return Hit.LOAD;
        return Hit.NONE;
    }

    /** Position of {@code seq} in {@code seqs}; {@code seqs.length} for the current build (seq 0). */
    static int indexOf(int[] seqs, int seq) {
        if (seq == 0) return seqs.length;
        for (int i = 0; i < seqs.length; i++) {
            if (seqs[i] == seq) return i;
        }
        return seqs.length;
    }

    /** The seq one step older than {@code seq}, or {@code seq} when there is none. */
    public static int older(int[] seqs, int seq) {
        int at = indexOf(seqs, seq);
        return at > 0 ? seqs[at - 1] : seq;
    }

    /** The seq one step newer than {@code seq}: the next frame, or 0 (current) past the newest. */
    public static int newer(int[] seqs, int seq) {
        if (seq == 0) return 0;
        int at = indexOf(seqs, seq);
        return at + 1 < seqs.length ? seqs[at + 1] : 0;
    }
}
