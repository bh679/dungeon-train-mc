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

    static final int COLUMNS = 2;
    /** Rows for the builder's four tiles; {@link #of} takes the real count and rounds up. */
    static final int ROWS = 2;
    static final int TILE_GAP = 6;
    static final int COLUMN_GAP = 8;
    static final int ROW_GAP = 4;
    static final int HEADER_H = 14;
    static final int GO_H = 20;

    /** Widest the body may get, so tiles don't become billboards on an ultrawide. */
    static final int MAX_BODY_WIDTH = 520;
    static final int SIDE_MARGIN = 16;
    static final int MIN_TILE_WIDTH = 64;
    /** The tiles' share of the body; the detail column takes the rest. */
    private static final double TILE_COLUMN_SHARE = 0.55;

    /**
     * @param screenWidth  screen width in GUI pixels
     * @param screenHeight screen height in GUI pixels
     * @param topY         first Y the body may occupy (below the title)
     * @param bottomY      first Y the body may NOT occupy (the bottom margin)
     */
    static BuilderPickerLayout of(int screenWidth, int screenHeight, int topY, int bottomY) {
        return of(screenWidth, screenHeight, topY, bottomY, COLUMNS * ROWS);
    }

    /** @param tileCount how many tiles the grid holds — rows are {@code ceil(tileCount / COLUMNS)} */
    static BuilderPickerLayout of(int screenWidth, int screenHeight, int topY, int bottomY, int tileCount) {
        int rows = Math.max(1, (tileCount + COLUMNS - 1) / COLUMNS);
        int minBodyH = rows * (MIN_TILE_WIDTH * 9 / 16) + TILE_GAP * (rows - 1) + ROW_GAP + GO_H;
        int bodyW = Math.max(COLUMNS * MIN_TILE_WIDTH + TILE_GAP + COLUMN_GAP + MIN_TILE_WIDTH,
                Math.min(screenWidth - 2 * SIDE_MARGIN, MAX_BODY_WIDTH));
        int bodyH = Math.max(bottomY - topY, minBodyH);
        int bodyX = (screenWidth - bodyW) / 2;

        int tileColumnW = (int) Math.round((bodyW - COLUMN_GAP) * TILE_COLUMN_SHARE);
        int detailW = bodyW - COLUMN_GAP - tileColumnW;
        int detailX = bodyX + tileColumnW + COLUMN_GAP;

        Rect go = new Rect(detailX, topY + bodyH - GO_H, detailW, GO_H);
        Rect back = new Rect(bodyX, go.y(), tileColumnW, GO_H);
        List<Rect> tiles = tiles(new Rect(bodyX, topY, tileColumnW, Math.max(0, back.y() - ROW_GAP - topY)), rows);

        Rect header = new Rect(detailX, topY, detailW, HEADER_H);
        int between = Math.max(0, go.y() - ROW_GAP - (header.bottom() + ROW_GAP));
        int previewH = Math.min(detailW * 9 / 16, between / 2);
        Rect preview = new Rect(detailX, header.bottom() + ROW_GAP, detailW, previewH);
        int descTop = preview.bottom() + ROW_GAP;
        Rect description = new Rect(detailX, descTop, detailW, Math.max(0, go.y() - ROW_GAP - descTop));

        return new BuilderPickerLayout(tiles, back, header, preview, description, go);
    }

    /**
     * Four 16:9 tiles, two per row, as large as the column allows and centred in it — the Nav
     * tab's arithmetic, with a floor so a tiny viewport still gets a tile you can click.
     */
    static List<Rect> tiles(Rect area) {
        return tiles(area, ROWS);
    }

    static List<Rect> tiles(Rect area, int rows) {
        int tileW = Math.max(1, (area.w() - TILE_GAP * (COLUMNS - 1)) / COLUMNS);
        int tileH = tileW * 9 / 16;
        int maxTileH = Math.max(1, (area.h() - TILE_GAP * (rows - 1)) / rows);
        if (tileH > maxTileH) {
            tileH = maxTileH;
            tileW = Math.max(1, tileH * 16 / 9);
            // Back through the same integer maths the width→height path uses, so a height-capped
            // grid is exactly 16:9 rather than one pixel off when 16/9 does not divide evenly.
            tileH = Math.max(1, tileW * 9 / 16);
        }
        tileW = Math.max(tileW, MIN_TILE_WIDTH);
        tileH = Math.max(tileH, MIN_TILE_WIDTH * 9 / 16);

        int gridW = COLUMNS * tileW + TILE_GAP * (COLUMNS - 1);
        int gridH = rows * tileH + TILE_GAP * (rows - 1);
        int x0 = area.x() + Math.max(0, (area.w() - gridW) / 2);
        int y0 = area.y() + Math.max(0, (area.h() - gridH) / 2);
        List<Rect> out = new ArrayList<>(COLUMNS * rows);
        for (int i = 0; i < COLUMNS * rows; i++) {
            out.add(new Rect(x0 + (i % COLUMNS) * (tileW + TILE_GAP), y0 + (i / COLUMNS) * (tileH + TILE_GAP),
                    tileW, tileH));
        }
        return List.copyOf(out);
    }
}
