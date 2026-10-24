package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.effects.crafter.CrafterPerks;
import games.brennan.dungeontrain.perks.effects.crafter.CrafterRank;
import net.minecraft.core.Holder;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.unimi.dsi.fastutil.objects.Object2IntMap;

/**
 * Crafter perks on the anvil:
 * <ul>
 *   <li><b>Enchant Cost</b>: once vanilla has priced the result, the price drops one level per rank
 *       (floor 1). The "too expensive" gate at 40 is judged before this and stays vanilla.</li>
 *   <li><b>Enchant Max</b>: vanilla clamps a combined level to {@code getMaxLevel()}, which would
 *       knock a table-rolled Sharpness VI back to V. The clamp becomes
 *       {@code max(vanillaMax + bonus, level already on either input)}: perk-rolled levels survive the
 *       anvil for everyone, and a perk holder can combine up to max + rank.</li>
 * </ul>
 */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuCrafterMixin extends ItemCombinerMenu {

    @Shadow @Final private DataSlot cost;

    private AnvilMenuCrafterMixin() {
        super(null, 0, null, null);
    }

    @Inject(method = "createResult", at = @At("TAIL"))
    private void dungeontrain$reduceCost(CallbackInfo ci) {
        if (this.resultSlots.getItem(0).isEmpty()) return;
        int priced = this.cost.get();
        int reduced = CrafterPerks.reducedCost(priced, CrafterRank.enchantCost(this.player));
        if (reduced != priced) this.cost.set(reduced);
    }

    @Redirect(method = "createResult",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/enchantment/Enchantment;getMaxLevel()I"))
    private int dungeontrain$raiseLevelCap(Enchantment enchantment) {
        int vanilla = enchantment.getMaxLevel();
        int onInputs = Math.max(dungeontrain$levelOn(this.inputSlots.getItem(0), enchantment),
                                dungeontrain$levelOn(this.inputSlots.getItem(1), enchantment));
        return CrafterPerks.anvilLevelCap(vanilla, CrafterRank.enchantMax(this.player), onInputs);
    }

    @Unique
    private static int dungeontrain$levelOn(ItemStack stack, Enchantment enchantment) {
        if (stack.isEmpty()) return 0;
        ItemEnchantments levels = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : levels.entrySet()) {
            if (entry.getKey().value() == enchantment) return entry.getIntValue();
        }
        return 0;
    }
}
