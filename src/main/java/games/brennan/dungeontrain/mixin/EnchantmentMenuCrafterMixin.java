package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.effects.crafter.CrafterEnchantMenu;
import games.brennan.dungeontrain.perks.effects.crafter.CrafterPerks;
import games.brennan.dungeontrain.perks.effects.crafter.CrafterRank;
import games.brennan.dungeontrain.perks.effects.crafter.EnchantContext;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Crafter perks on the enchanting table (see {@code perks/effects/crafter}):
 * <ul>
 *   <li><b>Enchant Cost</b>: {@code slotsChanged} is bracketed so {@code CrafterEnchantCost} (on
 *       NeoForge's level-set event) can find this menu's player and stash each row's untouched roll
 *       power; {@code getEnchantmentList} then rolls with that power instead of the lowered cost.</li>
 *   <li><b>Re-enchant</b>: the "no enchantments yet" half of {@code ItemStack#isEnchantable} is
 *       skipped, and the rolled list loses anything that clashes with what the item already has.</li>
 *   <li><b>Enchant Max</b>: the roll runs with the player's bonus on {@link EnchantContext} so
 *       {@code EnchantmentHelperCrafterMixin} can lift max-level results.</li>
 * </ul>
 */
@Mixin(EnchantmentMenu.class)
public abstract class EnchantmentMenuCrafterMixin implements CrafterEnchantMenu {

    @Unique
    private Player dungeontrain$player;

    @Unique
    private final int[] dungeontrain$rollPower = new int[3];

    @Override
    public Player dungeontrain$player() {
        return dungeontrain$player;
    }

    @Override
    public int[] dungeontrain$rollPower() {
        return dungeontrain$rollPower;
    }

    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/inventory/ContainerLevelAccess;)V",
            at = @At("RETURN"))
    private void dungeontrain$rememberPlayer(int containerId, Inventory inventory, ContainerLevelAccess access, CallbackInfo ci) {
        dungeontrain$player = inventory.player;
    }

    @Inject(method = "slotsChanged", at = @At("HEAD"))
    private void dungeontrain$enterSlotsChanged(CallbackInfo ci) {
        java.util.Arrays.fill(dungeontrain$rollPower, 0);
        EnchantContext.pushEnchantMenu(this);
    }

    @Inject(method = "slotsChanged", at = @At("RETURN"))
    private void dungeontrain$leaveSlotsChanged(CallbackInfo ci) {
        EnchantContext.popEnchantMenu();
    }

    /** Re-enchant: an already-enchanted item still counts as enchantable for a perk holder. */
    @Redirect(method = "slotsChanged",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isEnchantable()Z"))
    private boolean dungeontrain$allowEnchanted(ItemStack stack) {
        if (CrafterPerks.allowsReenchant(CrafterRank.reenchant(dungeontrain$player))) {
            return stack.getItem().isEnchantable(stack);
        }
        return stack.isEnchantable();
    }

    /** Enchant Cost: roll with the power vanilla computed, not the lowered requirement on show. */
    @ModifyVariable(method = "getEnchantmentList", at = @At("HEAD"), argsOnly = true, index = 4)
    private int dungeontrain$rollWithOriginalPower(int cost, RegistryAccess registryAccess, ItemStack stack, int slot) {
        if (slot < 0 || slot >= dungeontrain$rollPower.length) return cost;
        int original = dungeontrain$rollPower[slot];
        return original > 0 ? original : cost;
    }

    @Inject(method = "getEnchantmentList", at = @At("HEAD"))
    private void dungeontrain$enterRoll(RegistryAccess registryAccess, ItemStack stack, int slot, int cost,
                                        CallbackInfoReturnable<List<EnchantmentInstance>> cir) {
        EnchantContext.pushMaxLevelBonus(CrafterPerks.maxLevelBonus(CrafterRank.enchantMax(dungeontrain$player)));
    }

    /** Re-enchant: drop candidates that clash with the item's current enchantments, then leave the roll context. */
    @Inject(method = "getEnchantmentList", at = @At("RETURN"))
    private void dungeontrain$leaveRoll(RegistryAccess registryAccess, ItemStack stack, int slot, int cost,
                                        CallbackInfoReturnable<List<EnchantmentInstance>> cir) {
        EnchantContext.popMaxLevelBonus();
        List<EnchantmentInstance> rolled = cir.getReturnValue();
        if (rolled == null || rolled.isEmpty()) return;
        if (!CrafterPerks.allowsReenchant(CrafterRank.reenchant(dungeontrain$player))) return;
        ItemEnchantments existing = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        if (existing.isEmpty()) return;
        List<EnchantmentInstance> kept = CrafterPerks.compatibleWithExisting(rolled, existing.keySet(),
            (candidate, have) -> dungeontrain$fits(candidate, have, existing.getLevel(have)));
        if (kept.size() != rolled.size()) {
            rolled.clear();
            rolled.addAll(kept);
        }
    }

    /** Same enchantment only as an upgrade; anything else must be compatible with it. */
    @Unique
    private static boolean dungeontrain$fits(EnchantmentInstance candidate, Holder<Enchantment> have, int haveLevel) {
        if (candidate.enchantment.equals(have)) return candidate.level > haveLevel;
        return Enchantment.areCompatible(candidate.enchantment, have);
    }
}
