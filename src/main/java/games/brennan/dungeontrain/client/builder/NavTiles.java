package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderMode;

import java.util.ArrayList;
import java.util.List;

/**
 * The tile column both pickers share — the title screen's {@link TrainBuilderScreen} and the editor's Nav
 * tab: {@link BuilderMode#NAV_ORDER}, one full-width tile per row, in two tiers. The {@link BuilderMode#primary
 * primary} modes (Whole Carriages, Dimensional Carriages) get big 16:9 tiles; the advanced rest get short
 * ones, {@link #SMALL_SHARE} of that height, below an extra gap.
 *
 * <p>Pure arithmetic in plain ints so each screen can wrap the result in its own rect type and the fit can be
 * tested without a client.</p>
 */
public final class NavTiles {

    public static final int GAP = 6;
    /** A short tile's height as a share of a big one's. */
    static final double SMALL_SHARE = 0.4;
    /** Short enough to read as a row, tall enough for its caption. */
    static final int MIN_SMALL_H = 12;

    /** One tile: the mode it stands for and where it goes. */
    public record Cell(BuilderMode mode, int x, int y, int w, int h) {
        public int right() { return x + w; }
        public int bottom() { return y + h; }
    }

    private NavTiles() {}

    /** The tiles, in nav order, spanning the area's width and centred in its height. */
    public static List<Cell> layout(int x, int y, int w, int h) {
        List<BuilderMode> order = BuilderMode.NAV_ORDER;
        int big = (int) order.stream().filter(BuilderMode::primary).count();
        int small = order.size() - big;
        int gaps = GAP * (order.size() - 1) + (big > 0 && small > 0 ? GAP : 0);
        double units = big + SMALL_SHARE * small;
        int tileW = Math.max(1, w);
        int bigH = Math.max(1, Math.min(tileW * 9 / 16, (int) ((h - gaps) / units)));
        int smallH = Math.max(Math.min(MIN_SMALL_H, bigH), (int) Math.round(bigH * SMALL_SHARE));
        int total = big * bigH + small * smallH + gaps;
        int cursor = y + Math.max(0, (h - total) / 2);
        List<Cell> out = new ArrayList<>(order.size());
        boolean inBigTier = true;
        for (BuilderMode mode : order) {
            if (inBigTier && !mode.primary() && !out.isEmpty()) {
                cursor += GAP; // the tier break
                inBigTier = false;
            }
            int tileH = mode.primary() ? bigH : smallH;
            out.add(new Cell(mode, x, cursor, tileW, tileH));
            cursor += tileH + GAP;
        }
        return List.copyOf(out);
    }
}
