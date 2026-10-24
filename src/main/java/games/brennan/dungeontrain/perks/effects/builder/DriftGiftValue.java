package games.brennan.dungeontrain.perks.effects.builder;

import com.mojang.logging.LogUtils;
import games.brennan.tradeeverything.api.TradeEverythingApi;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * One item's trade value in sixteenths of an emerald, as Trade Everything (with DT's providers in
 * {@code compat/TradeEverythingBridge} and {@code compat/TradeValueTable}) prices it. The hard import
 * is confined here and gated the way the bridge is, so a build without TE pays nothing rather than
 * crashing.
 */
final class DriftGiftValue {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TE_MOD_ID = "tradeeverything";
    private static volatile boolean warned = false;

    private DriftGiftValue() {}

    /** Sixteenths per single item of {@code stack}; 0 when unknown, worthless or TE is absent. */
    static int sixteenthsPerItem(ItemStack stack) {
        if (stack.isEmpty() || !ModList.get().isLoaded(TE_MOD_ID)) return 0;
        try {
            ItemStack one = stack.copyWithCount(1);
            return Math.max(0, TradeEverythingApi.getValueSixteenths(one));
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LOGGER.warn("[DungeonTrain] Drift Gift Repaid could not price an item via Trade Everything: {}", t.toString());
            }
            return 0;
        }
    }
}
