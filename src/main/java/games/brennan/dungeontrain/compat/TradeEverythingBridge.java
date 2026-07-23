package games.brennan.dungeontrain.compat;

import games.brennan.tradeeverything.api.TradeEverythingApi;
import net.minecraft.world.item.Items;

import java.util.OptionalInt;

/**
 * Bridge into Trade Everything's valuation API. Hard imports are confined to
 * this class; the caller gates on {@code ModList.isLoaded("tradeeverything")}
 * + {@code catch (Throwable)} (same pattern as {@link EnderChestLockBridge})
 * so a build predating the API degrades gracefully.
 */
public final class TradeEverythingBridge {

    /**
     * 8 sixteenths × the default 0.75 payout margin = exactly 6 payout items
     * per book — "a narrative book is worth 6 coal/paper".
     */
    private static final int WRITTEN_BOOK_VALUE_SIXTEENTHS = 8;

    private TradeEverythingBridge() {}

    public static void install() {
        // All written books in DT are narrative artifacts (random/lectern/shared/
        // player-written) — flat value, overriding rarity/recipe derivation.
        TradeEverythingApi.registerValueProvider(stack ->
            stack.is(Items.WRITTEN_BOOK)
                ? OptionalInt.of(WRITTEN_BOOK_VALUE_SIXTEENTHS)
                : OptionalInt.empty());
    }
}
