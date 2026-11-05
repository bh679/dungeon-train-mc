package games.brennan.dungeontrain.mixin;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.trader.WanderingTraderStock;
import net.minecraft.world.entity.npc.WanderingTrader;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tops a wandering trader's list with its one Dungeon Train item ({@link WanderingTraderStock}). NeoForge's
 * {@code WandererTradesEvent} only adds to the pools vanilla rolls from, which can't promise an offer on
 * every trader. {@code updateTrades} runs once, when the trader's stock is first generated; saved offers
 * load without it, so a reload never adds a second.
 */
@Mixin(WanderingTrader.class)
public abstract class WanderingTraderStockMixin {

    @Unique
    private static final Logger dungeontrain$LOGGER = LogUtils.getLogger();

    @Inject(method = "updateTrades", at = @At("TAIL"))
    private void dungeontrain$stockDungeonTrainItem(CallbackInfo ci) {
        WanderingTrader self = (WanderingTrader) (Object) this;
        try {
            WanderingTraderStock.createOffer(self.getRandom())
                .ifPresent(offer -> self.getOffers().add(0, offer));
        } catch (RuntimeException e) {
            // Vanilla stock only — never propagate into getOffers, which opens the trade screen.
            dungeontrain$LOGGER.warn("[DungeonTrain] wandering trader stock failed; vanilla trades only", e);
        }
    }
}
