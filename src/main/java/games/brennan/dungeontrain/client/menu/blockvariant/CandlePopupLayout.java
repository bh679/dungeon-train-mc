package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.client.menu.MenuLang;
import games.brennan.dungeontrain.editor.VariantCandles;

import java.util.List;

/**
 * Geometry + labels of the Z menu's Candles popup for one candle row — opened from the same pill
 * slot as Grow (a candle never grows), drawn with the Grow popup's {@link GrowthPopupLayout.Row}s.
 *
 * <p>One section, <b>Count</b>: {@code As is | Rnd | 1 | 2 | 3 | 4}. Lit / unlit stays on the row's
 * On / R / Off pill.</p>
 */
final class CandlePopupLayout {

    static final int SECTION_COUNT = 0;

    private static final int OPTION_AS_PLACED = 0;
    private static final int OPTION_RANDOM = 1;

    private CandlePopupLayout() {}

    /** The popup's one row, positioned like the Grow popup. */
    static List<GrowthPopupLayout.Row> rows(VariantCandles candles, double panelW, double halfH) {
        String[] buttons = new String[2 + VariantCandles.MAX];
        buttons[OPTION_AS_PLACED] = MenuLang.t("block_variant.as_placed");
        buttons[OPTION_RANDOM] = MenuLang.t("block_variant.candles_random");
        for (int n = 1; n <= VariantCandles.MAX; n++) buttons[OPTION_RANDOM + n] = Integer.toString(n);
        double left = Math.max(-panelW / 2.0 + GrowthPopupLayout.PAD, -GrowthPopupLayout.POPUP_WIDTH / 2.0);
        double bottom = halfH + 2 * GrowthPopupLayout.PAD;
        return List.of(new GrowthPopupLayout.Row(SECTION_COUNT, MenuLang.t("block_variant.candles_count"),
            buttons, selected(candles), left, left + GrowthPopupLayout.POPUP_WIDTH,
            bottom, bottom + GrowthPopupLayout.ROW_HEIGHT));
    }

    /** The option clicking {@code option} picks. */
    static VariantCandles apply(int option) {
        if (option == OPTION_AS_PLACED) return VariantCandles.AS_PLACED;
        if (option == OPTION_RANDOM) return VariantCandles.RANDOM;
        return VariantCandles.fixed(option - OPTION_RANDOM);
    }

    /** Row pill label: the key word when as placed, else {@code Rnd} / the count. */
    static String shortLabel(VariantCandles candles) {
        if (candles.isDefault()) return MenuLang.t("block_variant.candles");
        return candles.isRandom() ? MenuLang.t("block_variant.candles_random") : "×" + candles.count();
    }

    private static int selected(VariantCandles candles) {
        if (candles.isDefault()) return OPTION_AS_PLACED;
        return candles.isRandom() ? OPTION_RANDOM : OPTION_RANDOM + candles.count();
    }
}
