package games.brennan.dungeontrain.mixin.tradeeverything;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.perks.effects.trader.ReducedReduction;
import games.brennan.tradeeverything.trade.DemandCurve;
import games.brennan.tradeeverything.trade.DemandLedger;
import games.brennan.tradeeverything.trade.VillagerDemands;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Reduced Reduction (Trader tier 2): scales how much of each Trade Anything sale the villager
 * remembers, by the trading player's rank. Wraps the one {@code withSale} call in
 * {@code VillagerDemands.recordSale}; TE's own {@code catch (Throwable)} around that call means
 * a failure here only loses the sale from the ledger. Follow-up: a TE API seam
 * ({@code registerSaleWeightProvider}) replaces this mixin.
 */
@Mixin(value = VillagerDemands.class, remap = false)
public abstract class VillagerDemandsReducedReductionMixin {

    @WrapOperation(method = "recordSale",
        at = @At(value = "INVOKE",
            target = "Lgames/brennan/tradeeverything/trade/DemandLedger;withSale(Ljava/lang/String;IDJLgames/brennan/tradeeverything/trade/DemandCurve;)Lgames/brennan/tradeeverything/trade/DemandLedger;"))
    private static DemandLedger dungeontrain$reducedReduction(DemandLedger ledger, String itemId, int count,
                                                            double valueEmeralds, long now, DemandCurve curve,
                                                            Operation<DemandLedger> original,
                                                            AbstractVillager villager, MerchantOffer offer) {
        double prev = ledger.soldEmeralds(itemId, now, curve);
        DemandLedger after = original.call(ledger, itemId, count, valueEmeralds, now, curve);
        return ReducedReduction.apply(villager, itemId, prev, after);
    }
}
