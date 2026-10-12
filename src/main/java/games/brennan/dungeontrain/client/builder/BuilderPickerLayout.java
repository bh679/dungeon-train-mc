package games.brennan.dungeontrain.client.builder;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry for the {@link TrainBuilderScreen} picker: four tiles on the left, the picked
 * mode's detail on the right.
 *
 * <p>The same shape as the editor's Nav tab ({@code EditorNavPane}): a 2×2 grid of 16:9 tiles
 * fills the left column, and the right column stacks the mode's name, its picture, the sentence
 * or two that says what it is for, and a <b>Go here</b> button along the bottom — with <b>Back</b>
 * on the same row under the tiles, so the two ways out sit side by side. Split out of the
 * screen so the fit can be unit-tested without a Minecraft client — the body has to survive GUI
 * scale 1 (a very wide, short viewport) through scale 4 (a small one) without any two rects
 * overlapping or running into the title.</p>
 */
record BuilderPickerLayout(List<Rect> tiles, Rect back, Rect header, Rect preview, Rect description, Rect go) {

    /** A rectangle in GUI pixels; {@code w}/{@code h} are never negative. */
    record Rect(int x, int y, int w, int h) {
        int right() { return x + w; }
        int bottom() { return y + h; }
    }

    /** One tile per row — a vertical list — so the preview column can be the wide one. */
    static final int COLUMNS = 1;
    static final int ROWS = games.brennan.dungeontrain.builder.BuilderMode.NAV_ORDER.size();
    static final int TILE_GAP = 6;
    static final int COLUMN_GAP = 8;
    static final int ROW_GAP = 4;
    static final int HEADER_H = 14;
    static final int GO_H = 20;

    /** Widest the body may get, so tiles don't become billboards on an ultrawide. */
    static final int MAX_BODY_WIDTH = 640;
    static final int SIDE_MARGIN = 16;
    static final int MIN_TILE_WIDTH = 64;
    /** The tiles' share of the body; the detail column takes the rest. */
    private static final double TILE_COLUMN_SHARE = 0.34;

    /**
     * @param screenWidth  screen width in GUI pixels
     * @param screenHeight screen height in GUI pixels
     * @param topY         first Y the body may occupy (below the title)
     * @param bottomY      first Y the body may NOT occupy (the bottom margin)
     */
    static BuilderPickerLayout of(int screenWidth, int screenHeight, int topY, int bottomY) {
        // Two tiers (NavTiles): the primary tiles at 16:9, the rest short, so the floor counts them apart.
        long primaries = games.brennan.dungeontrain.builder.BuilderMode.NAV_ORDER.stream()
                .filter(games.brennan.dungeontrain.builder.BuilderMode::primary).count();
        int minBodyH = (int) (primaries * (MIN_TILE_WIDTH * 9 / 16) + (ROWS - primaries) * NavTiles.MIN_SMALL_H)
                + NavTiles.GAP * ROWS + ROW_GAP + GO_H;
        int bodyW = Math.max(COLUMNS * MIN_TILE_WIDTH + TILE_GAP + COLUMN_GAP + MIN_TILE_WIDTH,
                Math.min(screenWidth - 2 * SIDE_MARGIN, MAX_BODY_WIDTH));
        int bodyH = Math.max(bottomY - topY, minBodyH);
        int bodyX = (screenWidth - bodyW) / 2;

        int tileColumnW = (int) Math.round((bodyW - COLUMN_GAP) * TILE_COLUMN_SHARE);
        int detailW = bodyW - COLUMN_GAP - tileColumnW;
        int detailX = bodyX + tileColumnW + COLUMN_GAP;

        Rect go = new Rect(detailX, topY + bodyH - GO_H, detailW, GO_H);
        Rect back = new Rect(bodyX, go.y(), tileColumnW, GO_H);
        List<Rect> tiles = tiles(new Rect(bodyX, topY, tileColumnW, Math.max(0, back.y() - ROW_GAP - topY)));

        Rect header = new Rect(detailX, topY, detailW, HEADER_H);
        int between = Math.max(0, go.y() - ROW_GAP - (header.bottom() + ROW_GAP));
        int previewH = Math.min(detailW * 9 / 16, between / 2);
        Rect preview = new Rect(detailX, header.bottom() + ROW_GAP, detailW, previewH);
        int descTop = preview.bottom() + ROW_GAP;
        Rect description = new Rect(detailX, descTop, detailW, Math.max(0, go.y() - ROW_GAP - descTop));

        return new BuilderPickerLayout(tiles, back, header, preview, description, go);
    }

    /**
     * One tile per mode in {@link games.brennan.dungeontrain.builder.BuilderMode#NAV_ORDER}: big tiles for
     * the two primary modes, short ones for the advanced rest — the Nav tab's arithmetic ({@link NavTiles}).
     */
    static List<Rect> tiles(Rect area) {
        // Tiles always span the column: the art is cover-cropped, so a tile shorter than 16:9 shows
        // a wider slice of its picture rather than a squashed one, and the caption has the width.
        // Two tiers, in nav order — see NavTiles.
        List<Rect> out = new ArrayList<>();
        for (NavTiles.Cell c : NavTiles.layout(area.x(), area.y(), area.w(), area.h())) {
            out.add(new Rect(c.x(), c.y(), c.w(), c.h()));
        }
        return List.copyOf(out);
    }
}
