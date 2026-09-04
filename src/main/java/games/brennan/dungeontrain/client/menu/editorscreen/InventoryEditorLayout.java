package games.brennan.dungeontrain.client.menu.editorscreen;

/**
 * Where everything on the inventory-style editor screen goes, for a given logical viewport.
 *
 * <p>A pure record so the fit can be tested at the sizes that matter — 427×240 (GUI scale 3 on a
 * 720p window) is the floor, 640×360 is comfortable. The preview gives way first, the sheet and
 * settings scroll, and the tile grid keeps its tile size and scrolls too. Nothing is drawn below
 * the hotbar reserve: the HUD keeps painting the hotbar under this screen and 1-9 keep working.</p>
 */
public record InventoryEditorLayout(
    Rect tabs, Rect panel,
    Rect filter, Rect typeStrip, Rect grid,
    Rect header, Rect preview, Rect sheet, Rect icons, Rect settings, Rect test,
    int tile
) {

    /** An axis-aligned rectangle in logical pixels. */
    public record Rect(int x, int y, int w, int h) {
        public int right() { return x + w; }
        public int bottom() { return y + h; }
        public boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
        public Rect inset(int by) {
            return new Rect(x + by, y + by, Math.max(0, w - by * 2), Math.max(0, h - by * 2));
        }
    }

    public static final int EDGE = 6;
    public static final int TAB_TOP = 4;
    public static final int TAB_H = 16;
    /** The hotbar plus a margin — the screen never paints over it. */
    public static final int HOTBAR_RESERVE = 26;
    public static final int PAD = 4;
    public static final int GAP = 4;
    public static final int FILTER_H = 14;
    public static final int STRIP_H = 14;
    public static final int HEADER_H = 14;
    public static final int SHEET_H = 62;
    public static final int ICONS_H = 20;
    public static final int TEST_H = 14;
    public static final int PREVIEW_MAX_W = 420;
    public static final int PREVIEW_MAX_H = 130;
    public static final int PREVIEW_MIN_H = 50;
    public static final int RIGHT_MIN_W = 150;
    public static final int RIGHT_MAX_W = 260;
    public static final int TILE_LARGE = 52;
    public static final int TILE_SMALL = 40;

    public static InventoryEditorLayout of(int width, int height) {
        int w = Math.max(0, width);
        int h = Math.max(0, height);
        Rect tabs = new Rect(EDGE, TAB_TOP, Math.max(0, w - EDGE * 2), TAB_H);
        int panelTop = TAB_TOP + TAB_H;
        Rect panel = new Rect(EDGE, panelTop, Math.max(0, w - EDGE * 2),
            Math.max(0, h - HOTBAR_RESERVE - panelTop));
        Rect inner = panel.inset(PAD);

        int rightW = clamp((int) Math.round(inner.w() * 0.40), RIGHT_MIN_W, RIGHT_MAX_W);
        rightW = Math.min(rightW, Math.max(0, inner.w() - GAP));
        int leftW = Math.max(0, inner.w() - rightW - GAP);
        Rect left = new Rect(inner.x(), inner.y(), leftW, inner.h());
        Rect right = new Rect(inner.right() - rightW, inner.y(), rightW, inner.h());

        Rect filter = new Rect(left.x(), left.y(), left.w(), FILTER_H);
        Rect strip = new Rect(left.x(), filter.bottom() + 2, left.w(), STRIP_H);
        Rect grid = new Rect(left.x(), strip.bottom() + 2, left.w(), Math.max(0, left.bottom() - strip.bottom() - 2));

        // Header, then the tools, then what they act on. The icon row sits above the model rather
        // than below the facts: it is the pane's toolbar, and a toolbar buried under six lines of
        // read-out is one the eye has to go looking for.
        Rect header = new Rect(right.x(), right.y(), right.w(), HEADER_H);
        Rect icons = new Rect(right.x(), header.bottom() + 1, right.w(), ICONS_H);
        int previewH = clamp((int) Math.round(right.h() * 0.34), PREVIEW_MIN_H, PREVIEW_MAX_H);
        int previewW = Math.min(right.w(), PREVIEW_MAX_W);
        Rect preview = new Rect(right.x(), icons.bottom() + 2, previewW, previewH);
        Rect sheet = new Rect(right.x(), preview.bottom() + 2, right.w(), SHEET_H);
        Rect test = new Rect(right.x(), right.bottom() - TEST_H, right.w(), TEST_H);
        Rect settings = new Rect(right.x(), sheet.bottom() + 2, right.w(),
            Math.max(0, test.y() - 2 - sheet.bottom() - 2));

        int tile = panel.h() < 210 ? TILE_SMALL : TILE_LARGE;
        return new InventoryEditorLayout(tabs, panel, filter, strip, grid,
            header, preview, sheet, icons, settings, test, tile);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
