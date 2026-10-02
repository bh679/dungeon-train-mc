package games.brennan.dungeontrain.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link TradeEverythingBridge#VILLAGER_STOCK_VALUES_256THS} — each hand-set
 * price quotes the intended row at a villager paying emeralds.
 */
final class TradeEverythingStockPricesTest {

    /** TE's default payout margin ({@code result_multiplier}). */
    private static final double PAYOUT_MARGIN = 0.75;
    /** One emerald in the 256ths the values are given in. */
    private static final int EMERALD = 256;
    /** TE's {@code TradePricer.ACCEPTABLE_OVERPAY}: a batch losing more than this is skipped for a bigger one. */
    private static final double ACCEPTABLE_ROUNDING_LOSS = 0.10;
    /** TE's default {@code max_cost_count}. */
    private static final int MAX_BATCH = 64;

    /**
     * The (items in, emeralds out) row TE's {@code TradePricer.quote} settles on
     * with its default {@code prefer_single_item_trades}: one item alone if it
     * affords an emerald, otherwise the first batch within the rounding tolerance.
     */
    private static int[] quotedRow(int value256ths) {
        double perItem = value256ths * PAYOUT_MARGIN;
        for (int items = 1; items <= MAX_BATCH; items++) {
            double paid = items * perItem;
            int emeralds = (int) Math.floor(paid / EMERALD);
            if (emeralds < 1) continue;
            if (items == 1 || (paid - emeralds * EMERALD) / paid <= ACCEPTABLE_ROUNDING_LOSS) {
                return new int[] {items, emeralds};
            }
        }
        throw new AssertionError("no batch within tolerance for value " + value256ths);
    }

    @ParameterizedTest(name = "{0}: {1} → {2} emerald(s)")
    @DisplayName("each price quotes its intended row")
    @CsvSource({
        "bell, 1, 2",
        "name_tag, 1, 1",
        "globe_banner_pattern, 1, 1",
        "iron_chestplate, 1, 5",
        "leather_chestplate, 1, 3",
        "ender_pearl, 1, 2",
        "item_frame, 5, 2",
        "map, 2, 1",
        "blue_ice, 7, 2",
    })
    void quotesIntendedRow(String path, int items, int emeralds) {
        Integer value = TradeEverythingBridge.VILLAGER_STOCK_VALUES_256THS.get(path);
        assertNotNull(value, path + " has no hand-set value");
        int[] row = quotedRow(value);
        assertEquals(items, row[0], path + " items in");
        assertEquals(emeralds, row[1], path + " emeralds out");
    }

    @Test
    @DisplayName("the rows above cover every hand-set price")
    void everyPriceIsCovered() {
        assertEquals(
            Set.of("bell", "name_tag", "globe_banner_pattern", "iron_chestplate", "leather_chestplate",
                "ender_pearl", "item_frame", "map", "blue_ice"),
            TradeEverythingBridge.VILLAGER_STOCK_VALUES_256THS.keySet());
    }
}
