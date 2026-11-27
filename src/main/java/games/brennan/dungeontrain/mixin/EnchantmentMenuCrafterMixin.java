package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.effects.crafter.CrafterEnchantMenu;
import games.brennan.dungeontrain.perks.effects.crafter.CrafterPerks;
import games.brennan.dungeontrain.perks.effects.crafter.CrafterRank;
import games.brennan.dungeontrain.perks.effects.crafter.EnchantContext;
import games.brennan.dungeontrain.perks.effects.crafter.Reenchant;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
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
 *       power; {@code getEnchantmentList} then rolls with that power instead of the lowered cost.
 *       The same per-row numbers ride three extra {@link DataSlot}s to the client, where
 *       {@code client.EnchantmentScreenCrafterCostMixin} shows them struck through beside the price.</li>
 *   <li><b>Re-enchant</b>: an already enchanted item or book with re-enchants left counts as
 *       enchantable ({@link Reenchant#canGoOnTable}); a book rolls and is priced as a plain book; the
 *       rolled list loses anything that clashes with what the item already has, and a row left with
 *       nothing is disabled (cost 0) and marked {@link CrafterEnchantMenu#NOTHING_NEW} for the screen.
 *       The click remembers it was a re-enchant so {@code ReenchantStamp} can count it on the item.</li>
 *   <li><b>Enchant Max</b>: the roll runs with the player's bonus on {@link EnchantContext} so
 *       {@code EnchantmentHelperCrafterMixin} can lift max-level results.</li>
 * </ul>
 */
@Mixin(EnchantmentMenu.class)
public abstract class EnchantmentMenuCrafterMixin extends AbstractContainerMenu implements CrafterEnchantMenu {

    private EnchantmentMenuCrafterMixin() {
        super(null, 0);
    }

    @Unique
    private Player dungeontrain$player;

    @Unique
    private final int[] dungeontrain$rollPower = new int[3];

    /** Depth of {@code slotsChanged} on this menu: a roll outside it is the click's re-roll. */
    @Unique
    private int dungeontrain$inSlotsChanged;

    @Unique
    private boolean dungeontrain$reenchantClick;

    @Override
    public boolean dungeontrain$takeReenchantClick() {
        boolean was = dungeontrain$reenchantClick;
        dungeontrain$reenchantClick = false;
        return was;
    }

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
        for (int row = 0; row < dungeontrain$rollPower.length; row++) {
            this.addDataSlot(DataSlot.shared(dungeontrain$rollPower, row));
        }
    }

    @Inject(method = "slotsChanged", at = @At("HEAD"))
    private void dungeontrain$enterSlotsChanged(CallbackInfo ci) {
        // Client side the array holds the server's synced values; vanilla never rolls there.
        if (dungeontrain$player != null && !dungeontrain$player.level().isClientSide()) {
            java.util.Arrays.fill(dungeontrain$rollPower, 0);
        }
        dungeontrain$inSlotsChanged++;
        EnchantContext.pushEnchantMenu(this);
    }

    @Inject(method = "slotsChanged", at = @At("RETURN"))
    private void dungeontrain$leaveSlotsChanged(CallbackInfo ci) {
        dungeontrain$inSlotsChanged = Math.max(0, dungeontrain$inSlotsChanged - 1);
        EnchantContext.popEnchantMenu();
    }

    /** Re-enchant: an already enchanted item or book with re-enchants left counts as enchantable. */
    @Redirect(method = "slotsChanged",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isEnchantable()Z"))
    private boolean dungeontrain$allowEnchanted(ItemStack stack) {
        return stack.isEnchantable() || Reenchant.canGoOnTable(dungeontrain$player, stack);
    }

    /** Re-enchant: an enchanted book rolls as a plain book. */
    @ModifyArg(method = "getEnchantmentList",
               at = @At(value = "INVOKE",
                        target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;selectEnchantment(Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/item/ItemStack;ILjava/util/stream/Stream;)Ljava/util/List;"),
               index = 1)
    private ItemStack dungeontrain$rollBookAsBook(ItemStack stack) {
        return Reenchant.rollStack(stack);
    }

    /** Re-enchant: an enchanted book loses one roll like a plain book does. */
    @WrapOperation(method = "getEnchantmentList",
                   at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;is(Lnet/minecraft/world/item/Item;)Z"))
    private boolean dungeontrain$bookTrim(ItemStack stack, Item item, Operation<Boolean> original) {
        return original.call(stack, item) || (item == Items.BOOK && stack.is(Items.ENCHANTED_BOOK));
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
        EnchantContext.pushEnchantMaxRank(CrafterRank.enchantMax(dungeontrain$player));
        if (dungeontrain$inSlotsChanged == 0) {
            // The click's re-roll, on the item before vanilla enchants it.
            dungeontrain$reenchantClick = CrafterRank.reenchant(dungeontrain$player) > 0 && Reenchant.isReenchant(stack);
        }
    }

    /**
     * Re-enchant: drop candidates that clash with the item's current enchantments, then leave the roll
     * context. A row left with nothing is disabled and marked "Nothing new" while the table lays out.
     */
    @Inject(method = "getEnchantmentList", at = @At("RETURN"))
    private void dungeontrain$leaveRoll(RegistryAccess registryAccess, ItemStack stack, int slot, int cost,
                                        CallbackInfoReturnable<List<EnchantmentInstance>> cir) {
        EnchantContext.popEnchantMaxRank();
        List<EnchantmentInstance> rolled = cir.getReturnValue();
        if (rolled == null || rolled.isEmpty()) return;
        if (CrafterRank.reenchant(dungeontrain$player) <= 0) return;
        ItemEnchantments existing = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        if (existing.isEmpty()) return;
        List<EnchantmentInstance> kept = CrafterPerks.compatibleWithExisting(rolled, existing.keySet(),
            (candidate, have) -> dungeontrain$fits(candidate, have, existing.getLevel(have)));
        if (kept.size() != rolled.size()) {
            rolled.clear();
            rolled.addAll(kept);
        }
        if (rolled.isEmpty() && dungeontrain$inSlotsChanged > 0 && slot >= 0 && slot < dungeontrain$rollPower.length) {
            ((EnchantmentMenu) (Object) this).costs[slot] = 0;
            dungeontrain$rollPower[slot] = CrafterEnchantMenu.NOTHING_NEW;
        }
    }

    /** Same enchantment only as an upgrade; anything else must be compatible with it. */
    @Unique
    private static boolean dungeontrain$fits(EnchantmentInstance candidate, Holder<Enchantment> have, int haveLevel) {
        if (candidate.enchantment.equals(have)) return candidate.level > haveLevel;
        return Enchantment.areCompatible(candidate.enchantment, have);
    }
}
