package games.brennan.dungeontrain.client.menu.editorscreen;

import games.brennan.dungeontrain.client.builder.RelayBuildPreviews;
import games.brennan.dungeontrain.client.menu.MenuRowPainter;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A thin timeline along the foot of a preview — the versions the relay recorded of a build, one
 * dot each, oldest on the left, with the build as it is now ("Current") at the right end.
 *
 * <p>Drawn only once the relay has said what it holds ({@link RelayBuildPreviews#versions}) and
 * holds something: a build the relay never saw shows no bar rather than a track with nothing on
 * it. A dot is a version; clicking one shows it in the preview above. A version saved from
 * something other than the version before it — a branch — is amber, with a riser to the version
 * it came from, so a history that forked reads as one. The selected version is ringed, and the
 * label on the left says which it is and, for an older one, where it came from.</p>
 */
public final class VersionStrip {

    /** The bar's height, the foot of the preview it overlays. */
    static final int H = 12;
    static final int PAD = 2;
    /** Room a dot needs on the track, and the smallest hit box a dot gets whatever the spacing. */
    static final int DOT_STEP_MIN = 6;
    static final int HIT_W = 7;
    static final int LABEL_MAX_SHARE_PCT = 45;

    static final int BAND = 0xC0101418;
    static final int TRACK = 0x60FFFFFF;
    static final int DOT = 0xFFC8C8C8;
    static final int DOT_BRANCH = 0xFFFFCC33;
    static final int DOT_CURRENT = 0xFF55FF55;
    static final int RING = 0xFFFFFFFF;
    static final int LABEL = 0xFFFFEEBB;
    static final int CAPTION = 0xFFC8C8C8;

    /** What a click landed on: a version dot (see {@link #hitSeq}), or the load button. */
    public enum Hit { NONE, SELECT, LOAD }

    /** Where each dot sits, in draw order: versions oldest→newest, then Current (seq 0). */
    private int[] dotX = new int[0];
    private int[] dotSeq = new int[0];
    private int dotY;
    private InventoryEditorLayout.Rect band;
    private InventoryEditorLayout.Rect load;
    private int hitSeq;

    /** Draw the bar along the foot of {@code preview}, if this build has versions to show. */
    public void draw(GuiGraphics g, Font font, InventoryEditorLayout.Rect preview, int relayId, int seq,
                     int mouseX, int mouseY) {
        draw(g, font, preview, relayId, seq, false, mouseX, mouseY);
    }

    /**
     * As above, with a <b>Load vN</b> button at the bar's right end while an older version is
     * selected — for the pane that has no load slot of its own (a template's own detail). Loading
     * replaces the template with that version; the relay's current one is untouched until the
     * next save.
     */
    public void draw(GuiGraphics g, Font font, InventoryEditorLayout.Rect preview, int relayId, int seq,
                     boolean offerLoad, int mouseX, int mouseY) {
        band = null;
        load = null;
        dotX = new int[0];
        dotSeq = new int[0];
        RelayBuildPreviews.VersionInfo info = relayId > 0 ? RelayBuildPreviews.versionInfo(relayId) : null;
        int[] seqs = info == null ? null : info.seqs();
        if (seqs == null || seqs.length < 1) return;

        int at = indexOf(seqs, seq);   // seqs.length means "current"
        band = new InventoryEditorLayout.Rect(preview.x(), preview.bottom() - H, preview.w(), H);
        g.fill(band.x(), band.y(), band.right(), band.bottom(), BAND);
        int textY = band.y() + (H - font.lineHeight) / 2 + 1;

        // Right end first: the load button, when offered, takes its width off the track.
        int right = band.right() - PAD;
        if (offerLoad && seq != 0) {
            String text = EditorScreenLang.text(EditorScreenLang.VERSION_LOAD, at + 1);
            int lw = font.width(text) + PAD * 4;
            load = new InventoryEditorLayout.Rect(right - lw, band.y(), lw, H);
            boolean hot = load.contains(mouseX, mouseY);
            g.fill(load.x(), load.y(), load.right(), load.bottom(), hot ? MenuRowPainter.CELL_HOVER : MenuRowPainter.CELL_IDLE);
            g.drawString(font, text, load.x() + PAD * 2, textY, hot ? MenuRowPainter.TEXT_ON_HOVER : 0xFFFFFFFF, false);
            right = load.x() - PAD * 2;
        }

        // Left: which version is selected, and for an older one where it came from. Capped so the
        // track keeps at least half the bar however long a builder's name runs.
        String label = seq == 0
            ? EditorScreenLang.text(EditorScreenLang.VERSION_CURRENT)
            : EditorScreenLang.text(EditorScreenLang.VERSION, at + 1, seqs.length);
        String caption = seq == 0 ? "" : caption(info, seqs, seq);
        int labelMax = Math.max(0, (right - band.x()) * LABEL_MAX_SHARE_PCT / 100);
        int x = band.x() + PAD;
        String shownLabel = font.plainSubstrByWidth(label, labelMax);
        g.drawString(font, shownLabel, x, textY, LABEL, false);
        x += font.width(shownLabel);
        if (!caption.isEmpty()) {
            String shownCaption = font.plainSubstrByWidth(" \u00B7 " + caption, Math.max(0, band.x() + PAD + labelMax - x));
            g.drawString(font, shownCaption, x, textY, CAPTION, false);
            x += font.width(shownCaption);
        }
        int trackLeft = x + PAD * 3;
        int trackRight = right - PAD;
        if (trackRight - trackLeft < DOT_STEP_MIN * 2) return;   // no room for a track worth the name

        // The track: versions spread evenly, Current at the far end.
        int n = seqs.length + 1;
        dotX = new int[n];
        dotSeq = new int[n];
        dotY = band.y() + H / 2;
        g.fill(trackLeft, dotY, trackRight + 1, dotY + 1, TRACK);
        for (int i = 0; i < n; i++) {
            dotX[i] = n == 1 ? trackRight : trackLeft + (trackRight - trackLeft) * i / (n - 1);
            dotSeq[i] = i < seqs.length ? seqs[i] : 0;
        }
        // Risers before dots, so a fork's line runs under the dots it joins: from a branch up to
        // the version it was saved from, along the top of the band.
        for (int i = 1; i < seqs.length; i++) {
            int parent = info.parentOf(seqs[i]);
            int parentAt = parent > 0 ? indexOf(seqs, parent) : seqs.length;
            if (parentAt >= i || parentAt == i - 1) continue;   // linear, or nothing the strip can place
            int top = band.y() + 1;
            g.fill(dotX[parentAt], top, dotX[parentAt] + 1, dotY, DOT_BRANCH & 0x80FFFFFF);
            g.fill(dotX[parentAt], top, dotX[i] + 1, top + 1, DOT_BRANCH & 0x80FFFFFF);
            g.fill(dotX[i], top, dotX[i] + 1, dotY, DOT_BRANCH & 0x80FFFFFF);
        }
        int hover = dotAt(mouseX, mouseY);
        for (int i = 0; i < n; i++) {
            boolean current = i == seqs.length;
            boolean branch = !current && isBranch(info, seqs, i);
            boolean selected = current ? seq == 0 : seqs[i] == seq;
            int colour = current ? DOT_CURRENT : branch ? DOT_BRANCH : DOT;
            int cx = dotX[i];
            if (selected || i == hover) {
                g.fill(cx - 2, dotY - 2, cx + 3, dotY + 3, selected ? RING : (colour & 0x80FFFFFF));
                g.fill(cx - 1, dotY - 1, cx + 2, dotY + 2, colour);
            } else {
                g.fill(cx - 1, dotY - 1, cx + 2, dotY + 2, colour);
            }
        }
        // A hovered dot names itself above the bar, so a track of identical dots can be read.
        if (hover >= 0) {
            String tip = hover == seqs.length
                ? EditorScreenLang.text(EditorScreenLang.VERSION_CURRENT)
                : tooltip(info, seqs, seqs[hover]);
            int tw = font.width(tip) + PAD * 2;
            int tx = Math.max(preview.x(), Math.min(dotX[hover] - tw / 2, preview.right() - tw));
            int ty = band.y() - H - 1;
            g.fill(tx, ty, tx + tw, ty + H, BAND);
            g.drawString(font, tip, tx + PAD, ty + (H - font.lineHeight) / 2 + 1, 0xFFFFFFFF, false);
        }
    }

    /** Whether the version at {@code i} was saved from something other than the version before it. */
    static boolean isBranch(RelayBuildPreviews.VersionInfo info, int[] seqs, int i) {
        if (i <= 0) return false;
        int parent = info.parentOf(seqs[i]);
        if (parent <= 0) return false;
        int parentAt = indexOf(seqs, parent);
        return parentAt < seqs.length && parentAt != i - 1;
    }

    /** The dot under the mouse, or -1. Hit boxes are the whole band height and at least {@link #HIT_W} wide. */
    private int dotAt(double mx, double my) {
        if (band == null || dotX.length == 0 || my < band.y() || my >= band.bottom()) return -1;
        int step = dotX.length > 1 ? dotX[1] - dotX[0] : HIT_W;
        int half = Math.max(HIT_W, step) / 2;
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < dotX.length; i++) {
            double d = Math.abs(mx - dotX[i]);
            if (d <= half && d < bestDist) { best = i; bestDist = d; }
        }
        return best;
    }

    /** What a click at this point means; a {@link Hit#SELECT} names its version in {@link #hitSeq}. */
    public Hit hit(double mx, double my) {
        if (load != null && load.contains(mx, my)) return Hit.LOAD;
        int at = dotAt(mx, my);
        if (at >= 0) {
            hitSeq = dotSeq[at];
            return Hit.SELECT;
        }
        return Hit.NONE;
    }

    /** The version the last {@link Hit#SELECT} landed on (0 = Current). */
    public int hitSeq() {
        return hitSeq;
    }

    /**
     * What to say beside an older version: "from vN" for the version it was saved from, with "by
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

    /** A dot's tooltip: its number, then whatever the caption has to say about it. */
    static String tooltip(RelayBuildPreviews.VersionInfo info, int[] seqs, int seq) {
        String head = EditorScreenLang.text(EditorScreenLang.VERSION, indexOf(seqs, seq) + 1, seqs.length);
        String rest = caption(info, seqs, seq);
        return rest.isEmpty() ? head : head + " \u00B7 " + rest;
    }

    /** Position of {@code seq} in {@code seqs}; {@code seqs.length} for the current build (seq 0). */
    static int indexOf(int[] seqs, int seq) {
        if (seq == 0) return seqs.length;
        for (int i = 0; i < seqs.length; i++) {
            if (seqs[i] == seq) return i;
        }
        return seqs.length;
    }

    /** The seq one step older than {@code seq}, or {@code seq} when there is none — the ← key. */
    public static int older(int[] seqs, int seq) {
        int at = indexOf(seqs, seq);
        return at > 0 ? seqs[at - 1] : seq;
    }

    /** The seq one step newer than {@code seq}: the next version, or 0 (current) past the newest — the → key. */
    public static int newer(int[] seqs, int seq) {
        if (seq == 0) return 0;
        int at = indexOf(seqs, seq);
        return at + 1 < seqs.length ? seqs[at + 1] : 0;
    }
}
