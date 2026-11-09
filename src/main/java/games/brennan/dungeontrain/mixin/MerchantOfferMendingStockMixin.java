package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.difficulty.MendingTradeStock;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Caps a Mending trade's stock at {@link MendingTradeStock#MAX_STOCK}. Every public constructor,
 * the copy constructor and both codecs (save load, client sync) end in the private 10-arg
 * constructor, so one inject there covers new, saved and synced offers alike.
 */
@Mixin(MerchantOffer.class)
public abstract class MerchantOfferMendingStockMixin {

    @Shadow @Final private ItemStack result;
    @Shadow @Final @Mutable private int maxUses;

    @Inject(method = "<init>(Lnet/minecraft/world/item/trading/ItemCost;Ljava/util/Optional;Lnet/minecraft/world/item/ItemStack;IIZIIFI)V",
            at = @At("RETURN"))
    private void dungeontrain$capMendingStock(ItemCost baseCostA, Optional<ItemCost> costB, ItemStack result,
                                               int uses, int maxUses, boolean rewardExp, int specialPriceDiff,
                                               int demand, float priceMultiplier, int xp, CallbackInfo ci) {
        this.maxUses = MendingTradeStock.limitStock(this.maxUses, this.result);
    }
}
