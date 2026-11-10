package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.trader.ExtraCostContainer;
import games.brennan.dungeontrain.trader.ExtraTradeCosts;
import net.minecraft.core.NonNullList;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * A trade with extra costs ({@link ExtraTradeCosts}) shows its result only once every extra cost
 * sits in the menu's extra-cost slots, and the result it shows has the cost marker stripped, so
 * the item the player takes is plain. Runs after vanilla (and Trade Everything's repricing) picked
 * the active offer, and never touches a trade without extra costs.
 */
@Mixin(MerchantContainer.class)
public abstract class MerchantContainerExtraCostsMixin implements ExtraCostContainer {

    @Shadow private int selectionHint;
    @Shadow @Nullable private MerchantOffer activeOffer;
    @Shadow private int futureXp;
    @Shadow @Final private NonNullList<ItemStack> itemStacks;

    @Unique @Nullable private SimpleContainer dungeontrain$backing;

    @Override
    public SimpleContainer dungeontrain$backing() {
        return dungeontrain$backing;
    }

    @Override
    public void dungeontrain$setBacking(SimpleContainer backing) {
        this.dungeontrain$backing = backing;
    }

    @Override
    public int dungeontrain$selectionHint() {
        return selectionHint;
    }

    @Inject(method = "updateSellItem", at = @At("RETURN"))
    private void dungeontrain$requireExtraCosts(CallbackInfo ci) {
        ItemStack result = itemStacks.get(2);
        if (result.isEmpty() || activeOffer == null) return;
        List<ItemCost> extras = ExtraTradeCosts.of(activeOffer);
        if (extras.isEmpty()) return;
        if (dungeontrain$backing == null || !ExtraTradeCosts.isPaid(extras, dungeontrain$backing)) {
            itemStacks.set(2, ItemStack.EMPTY);
            futureXp = 0;
            return;
        }
        itemStacks.set(2, ExtraTradeCosts.strip(result));
    }
}
