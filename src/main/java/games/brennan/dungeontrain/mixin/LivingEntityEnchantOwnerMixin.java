package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.effects.crafter.EnchantContext;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Enchant Bonus reach for attribute enchantments (Efficiency, Depth Strider, Swift Sneak, Respiration,
 * Sweeping Edge and friends): their modifiers are collected in {@code handleEquipmentChanges} from
 * {@code ItemStack#forEachModifier}, which reads enchantments with no entity in hand. The entity is
 * pushed as owner for that call so {@code CrafterEnchantBonus} can raise the levels.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityEnchantOwnerMixin {

    @Inject(method = "handleEquipmentChanges", at = @At("HEAD"))
    private void dungeontrain$enterEquipmentChanges(Map<EquipmentSlot, ItemStack> equipments, CallbackInfo ci) {
        EnchantContext.pushOwner((Entity) (Object) this);
    }

    @Inject(method = "handleEquipmentChanges", at = @At("RETURN"))
    private void dungeontrain$leaveEquipmentChanges(Map<EquipmentSlot, ItemStack> equipments, CallbackInfo ci) {
        EnchantContext.popOwner();
    }
}
