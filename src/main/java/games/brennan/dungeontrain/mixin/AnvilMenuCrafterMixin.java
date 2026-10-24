package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.effects.crafter.CrafterPerks;
import games.brennan.dungeontrain.perks.effects.crafter.CrafterRank;
import net.minecraft.core.Holder;
import games.brennan.dungeontrain.perks.effects.crafter.CrafterAnvilMenu;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.objectweb.asm.Opcodes;
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
 *       (floor 1). Vanilla empties the result once the price reaches 40 unless the player is in
 *       creative; that creative check reads true only when the discount brings this combine's
 *       price back under 40 ({@link CrafterPerks#liftsTooExpensive}). A combine still at 40+ after
 *       the discount stays "Too Expensive!". The per-combine verdict rides an extra
 *       {@link DataSlot} to the client ({@code client.AnvilScreenCrafterCapMixin}). The
 *       pre-discount price rides a second data slot so the screen can show it struck through
 *       before the price paid. Rank 0 stays vanilla.</li>
 *   <li><b>Enchant Max</b>: vanilla clamps a combined level to {@code getMaxLevel()}, which would
 *       knock a table-rolled Sharpness VI back to V. The clamp becomes
 *       {@code max(vanillaMax + bonus, level already on either input)}: perk-rolled levels survive the
 *       anvil for everyone, and a perk holder can combine up to max + rank.</li>
 * </ul>
 */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuCrafterMixin extends ItemCombinerMenu implements CrafterAnvilMenu {

    @Shadow @Final private DataSlot cost;

    /** 1 when the current combine gets past the 40-level cap; set on the server per combine, synced to the client. */
    @Unique
    private final DataSlot dungeontrain$uncapped = DataSlot.standalone();

    /** Vanilla's price before Enchant Cost lowered it, 0 when nothing was taken off; synced to the client. */
    @Unique
    private final DataSlot dungeontrain$originalCost = DataSlot.standalone();

    private AnvilMenuCrafterMixin() {
        super(null, 0, null, null);
    }

    @Override
    public boolean dungeontrain$uncapped() {
        return dungeontrain$uncapped.get() == 1;
    }

    @Override
    public int dungeontrain$originalCost() {
        return dungeontrain$originalCost.get();
    }

    @Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/inventory/ContainerLevelAccess;)V",
            at = @At("RETURN"))
    private void dungeontrain$addUncappedSlot(int containerId, Inventory inventory, ContainerLevelAccess access, CallbackInfo ci) {
        this.addDataSlot(dungeontrain$uncapped);
        this.addDataSlot(dungeontrain$originalCost);
    }

    @Inject(method = "createResult", at = @At("HEAD"))
    private void dungeontrain$enterCreateResult(CallbackInfo ci) {
        // Early returns skip the later hooks, so clear the per-combine state here.
        if (dungeontrain$isServer()) {
            dungeontrain$originalCost.set(0);
            dungeontrain$uncapped.set(0);
        }
    }

    @Unique
    private boolean dungeontrain$isServer() {
        return this.player != null && !this.player.level().isClientSide();
    }

    /**
     * Enchant Cost: vanilla's {@code cost >= 40 && !instabuild} empties the result. Its creative
     * check (the second {@code instabuild} read in {@code createResult}; the first lets creative
     * players add incompatible enchantments and stays vanilla) is only reached with the vanilla
     * price at 40+, and reads true when the discount brings that price under 40. The server
     * decides and records the verdict; the client reads the last synced one.
     */
    @Redirect(method = "createResult",
              at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Abilities;instabuild:Z",
                       opcode = Opcodes.GETFIELD, ordinal = 1),
              require = 1)
    private boolean dungeontrain$skipTooExpensive(Abilities abilities) {
        if (dungeontrain$isServer()) {
            boolean lifted = CrafterPerks.liftsTooExpensive(this.cost.get(), CrafterRank.enchantCost(this.player));
            dungeontrain$uncapped.set(lifted ? 1 : 0);
        }
        return abilities.instabuild || dungeontrain$uncapped();
    }

    @Inject(method = "createResult", at = @At("TAIL"))
    private void dungeontrain$reduceCost(CallbackInfo ci) {
        if (this.resultSlots.getItem(0).isEmpty()) return;
        int priced = this.cost.get();
        int reduced = CrafterPerks.reducedCost(priced, CrafterRank.enchantCost(this.player));
        if (reduced != priced) {
            this.cost.set(reduced);
            if (!this.player.level().isClientSide()) dungeontrain$originalCost.set(priced);
        }
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
