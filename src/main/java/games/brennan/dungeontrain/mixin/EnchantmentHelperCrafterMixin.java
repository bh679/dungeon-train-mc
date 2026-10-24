package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.effects.crafter.CrafterPerks;
import games.brennan.dungeontrain.perks.effects.crafter.EnchantContext;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.stream.Stream;

/**
 * Crafter perks inside the static enchantment helper, which knows no player:
 * <ul>
 *   <li><b>Enchant Max</b>: a table roll that landed on an enchantment's vanilla max climbs by the
 *       bonus {@code EnchantmentMenuCrafterMixin} put on {@link EnchantContext} for the roll.</li>
 *   <li><b>Enchant Bonus</b>: the paths that read a known entity's gear (attack damage, knockback,
 *       fall damage, post-attack effects, equipment iteration, the per-entity level lookup) push that
 *       entity as the owner for their duration, so {@code CrafterEnchantBonus} can raise the levels.</li>
 * </ul>
 */
@Mixin(EnchantmentHelper.class)
public abstract class EnchantmentHelperCrafterMixin {

    @Inject(method = "getAvailableEnchantmentResults", at = @At("RETURN"))
    private static void dungeontrain$rollPastMax(int level, ItemStack stack, Stream<Holder<Enchantment>> possible,
                                                 CallbackInfoReturnable<List<EnchantmentInstance>> cir) {
        int bonus = EnchantContext.maxLevelBonus();
        if (bonus <= 0) return;
        List<EnchantmentInstance> list = cir.getReturnValue();
        if (list == null || list.isEmpty()) return;
        for (int i = 0; i < list.size(); i++) {
            EnchantmentInstance rolled = list.get(i);
            int raised = CrafterPerks.rolledLevel(rolled.level, rolled.enchantment.value().getMaxLevel(), bonus);
            if (raised != rolled.level) list.set(i, new EnchantmentInstance(rolled.enchantment, raised));
        }
    }

    @Inject(method = {"modifyDamage", "modifyKnockback", "modifyFallBasedDamage"}, at = @At("HEAD"))
    private static void dungeontrain$enterAttack(ServerLevel level, ItemStack tool, Entity target, DamageSource source,
                                                 float value, CallbackInfoReturnable<Float> cir) {
        EnchantContext.pushOwner(source.getEntity());
    }

    @Inject(method = {"modifyDamage", "modifyKnockback", "modifyFallBasedDamage"}, at = @At("RETURN"))
    private static void dungeontrain$leaveAttack(ServerLevel level, ItemStack tool, Entity target, DamageSource source,
                                                 float value, CallbackInfoReturnable<Float> cir) {
        EnchantContext.popOwner();
    }

    @Inject(method = "doPostAttackEffectsWithItemSource", at = @At("HEAD"))
    private static void dungeontrain$enterPostAttack(ServerLevel level, Entity target, DamageSource source,
                                                     ItemStack itemSource, CallbackInfo ci) {
        EnchantContext.pushOwner(source.getEntity());
    }

    @Inject(method = "doPostAttackEffectsWithItemSource", at = @At("RETURN"))
    private static void dungeontrain$leavePostAttack(ServerLevel level, Entity target, DamageSource source,
                                                     ItemStack itemSource, CallbackInfo ci) {
        EnchantContext.popOwner();
    }

    @Inject(method = "getEnchantmentLevel(Lnet/minecraft/core/Holder;Lnet/minecraft/world/entity/LivingEntity;)I",
            at = @At("HEAD"))
    private static void dungeontrain$enterEntityLevel(Holder<Enchantment> enchantment, LivingEntity entity,
                                                      CallbackInfoReturnable<Integer> cir) {
        EnchantContext.pushOwner(entity);
    }

    @Inject(method = "getEnchantmentLevel(Lnet/minecraft/core/Holder;Lnet/minecraft/world/entity/LivingEntity;)I",
            at = @At("RETURN"))
    private static void dungeontrain$leaveEntityLevel(Holder<Enchantment> enchantment, LivingEntity entity,
                                                      CallbackInfoReturnable<Integer> cir) {
        EnchantContext.popOwner();
    }

    @Inject(method = "runIterationOnItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlot;"
                     + "Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/enchantment/EnchantmentHelper$EnchantmentInSlotVisitor;)V",
            at = @At("HEAD"))
    private static void dungeontrain$enterSlotIteration(ItemStack stack, EquipmentSlot slot, LivingEntity entity,
                                                        EnchantmentHelper.EnchantmentInSlotVisitor visitor, CallbackInfo ci) {
        EnchantContext.pushOwner(entity);
    }

    @Inject(method = "runIterationOnItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlot;"
                     + "Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/enchantment/EnchantmentHelper$EnchantmentInSlotVisitor;)V",
            at = @At("RETURN"))
    private static void dungeontrain$leaveSlotIteration(ItemStack stack, EquipmentSlot slot, LivingEntity entity,
                                                        EnchantmentHelper.EnchantmentInSlotVisitor visitor, CallbackInfo ci) {
        EnchantContext.popOwner();
    }
}
