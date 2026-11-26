package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.perks.effects.hoarder.HoarderLootingEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hoarder Looting: brackets a mob's loot-table drop so the killer's perk can count as extra Looting on
 * their weapon while the table reads it ({@link HoarderLootingEvents}).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityLootingPerkMixin {

    @Unique
    private Object dungeontrain$lootingHold;

    @Inject(method = "dropFromLootTable(Lnet/minecraft/world/damagesource/DamageSource;Z)V", at = @At("HEAD"))
    private void dungeontrain$enterLooting(DamageSource source, boolean hitByPlayer, CallbackInfo ci) {
        dungeontrain$lootingHold = HoarderLootingEvents.enter((LivingEntity) (Object) this, source);
    }

    @Inject(method = "dropFromLootTable(Lnet/minecraft/world/damagesource/DamageSource;Z)V", at = @At("RETURN"))
    private void dungeontrain$exitLooting(DamageSource source, boolean hitByPlayer, CallbackInfo ci) {
        Object previous = dungeontrain$lootingHold;
        dungeontrain$lootingHold = null;
        HoarderLootingEvents.exit(previous);
    }
}
