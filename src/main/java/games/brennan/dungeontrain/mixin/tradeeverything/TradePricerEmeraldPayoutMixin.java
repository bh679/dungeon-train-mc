package games.brennan.dungeontrain.mixin.tradeeverything;

import games.brennan.dungeontrain.compat.TradeEverythingBridge;
import games.brennan.tradeeverything.config.TradeEverythingConfig;
import games.brennan.tradeeverything.trade.TradePricer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pays DT's emerald-priced items (potions) out in emeralds.
 *
 * <p>Trade Everything pays out in the villager's own goods (wheat, paper…) and
 * only switches to emeralds once one input overflows a stack of them, so a
 * potion worth 1 emerald came back as ~20 wheat. Raising its value can't fix
 * that without pricing it past a stack of every profession's goods. TE's
 * {@code BuyItemSelector} API doesn't see the input, but every quote path —
 * payment slot, carried item, inventory preview — goes through
 * {@code payoutFor(input, …)}, so this is the one place to decide it.</p>
 */
@Mixin(value = TradePricer.class, remap = false)
public abstract class TradePricerEmeraldPayoutMixin {

    @Inject(method = "payoutFor", at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$payEmeraldsForPotions(ItemStack input, Item preferred,
                                                          MerchantOffers offers, TradeEverythingConfig config,
                                                          CallbackInfoReturnable<Item> cir) {
        if (TradeEverythingBridge.paysInEmeralds(input)) cir.setReturnValue(Items.EMERALD);
    }
}
