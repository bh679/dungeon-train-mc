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
 *       (floor 1). A perk holder (rank 1+) also loses the "Too Expensive!" cap: vanilla empties the
 *       result once the price reaches 40 unless the player is in creative, and that creative check
 *       reads true for them, so any price can be paid by a player with the levels (survival
 *       included). The verdict rides an extra {@link DataSlot} to the client, where
 *       {@code client.AnvilScreenCrafterCapMixin} shows the price instead of "Too Expensive!".
 *       The pre-discount price rides a second data slot so the screen can show it struck through
 *       before the price paid.
 *       Rank 0 stays vanilla.</li>
 *   <li><b>Enchant Max</b>: vanilla clamps a combined level to {@code getMaxLevel()}, which would
 *       knock a table-rolled Sharpness VI back to V. The clamp becomes
 *       {@code max(vanillaMax + bonus, level already on either input)}: perk-rolled levels survive the
 *       anvil for everyone, and a perk holder can combine up to max + rank.</li>
 * </ul>
 */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuCrafterMixin extends ItemCombinerMenu implements CrafterAnvilMenu {

    @Shadow @Final private DataSlot cost;

    /** 1 when this anvil ignores the 40-level cap; set on the server, synced to the client. */
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
        dungeontrain$refreshUncapped();
    }

    @Inject(method = "createResult", at = @At("HEAD"))
    private void dungeontrain$enterCreateResult(CallbackInfo ci) {
        // Early returns skip the TAIL hook, so clear the struck price here.
        if (this.player != null && !this.player.level().isClientSide()) dungeontrain$originalCost.set(0);
        dungeontrain$refreshUncapped();
    }

    /** Server only: the client keeps whatever the server last synced. */
    @Unique
    private void dungeontrain$refreshUncapped() {
        if (this.player == null || this.player.level().isClientSide()) return;
        dungeontrain$uncapped.set(CrafterPerks.ignoresAnvilCap(CrafterRank.enchantCost(this.player)) ? 1 : 0);
    }

    /**
     * Enchant Cost: vanilla's {@code cost >= 40 && !instabuild} empties the result. Its creative
     * check (the second {@code instabuild} read in {@code createResult}; the first lets creative
     * players add incompatible enchantments and stays vanilla) reads true for a perk holder.
     */
    @Redirect(method = "createResult",
              at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Abilities;instabuild:Z",
                       opcode = Opcodes.GETFIELD, ordinal = 1),
              require = 1)
    private boolean dungeontrain$skipTooExpensive(Abilities abilities) {
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
