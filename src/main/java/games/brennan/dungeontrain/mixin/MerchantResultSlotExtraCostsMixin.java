package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.provenance.ProvenanceSweep;
import games.brennan.dungeontrain.trader.ExtraCostContainer;
import games.brennan.dungeontrain.trader.ExtraTradeCosts;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Takes a trade's extra costs out of the extra-cost slots when the trade goes through: at the
 * point vanilla has just taken costs A and B ({@code notifyTrade} runs only after that succeeded).
 * Also writes the buyer into a bought piece of gear's provenance.
 */
@Mixin(MerchantResultSlot.class)
public abstract class MerchantResultSlotExtraCostsMixin {

    @Shadow @Final private MerchantContainer slots;

    /** A bought item is the buyer's: written in before it reaches their inventory (ItemProvenance). */
    @Inject(method = "onTake", at = @At("HEAD"))
    private void dungeontrain$markBought(Player player, ItemStack stack, CallbackInfo ci) {
        ProvenanceSweep.onBought(player, stack);
    }

    @Inject(method = "onTake",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/item/trading/Merchant;notifyTrade(Lnet/minecraft/world/item/trading/MerchantOffer;)V"))
    private void dungeontrain$takeExtraCosts(Player player, ItemStack stack, CallbackInfo ci) {
        MerchantOffer offer = slots.getActiveOffer();
        List<ItemCost> extras = ExtraTradeCosts.of(offer);
        SimpleContainer backing = ((ExtraCostContainer) slots).dungeontrain$backing();
        if (!extras.isEmpty() && backing != null) ExtraTradeCosts.take(extras, backing);
    }
}
