package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.effects.crafter.EnchantContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Enchant Bonus reach for Unbreaking: durability loss runs through
 * {@code EnchantmentHelper#processDurabilityChange}, which has the stack but not its holder. The
 * entity taking the hit is pushed as owner around {@code hurtAndBreak}.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackEnchantOwnerMixin {

    @Inject(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"))
    private void dungeontrain$enterHurt(int amount, ServerLevel level, LivingEntity entity, Consumer<Item> onBreak, CallbackInfo ci) {
        EnchantContext.pushOwner(entity);
    }

    @Inject(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V",
            at = @At("RETURN"))
    private void dungeontrain$leaveHurt(int amount, ServerLevel level, LivingEntity entity, Consumer<Item> onBreak, CallbackInfo ci) {
        EnchantContext.popOwner();
    }
}
