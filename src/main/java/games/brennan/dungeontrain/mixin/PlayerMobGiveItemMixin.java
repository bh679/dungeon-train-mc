package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.advancement.PlayerMobSocialTracker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Observes the bundled playermob mod's
 * {@code PlayerMobEntity.giveItemTo(LivingEntity)} — a FRIENDLY passenger
 * handing the player a gift (its {@code FriendlyGreetGoal}). The tossed
 * {@code ItemEntity} carries no thrower, so the give can't be attributed from
 * the item alone; this inject records it directly against the (player, mob)
 * pair in {@link PlayerMobSocialTracker}, unlocking <em>A Silent Friend</em>
 * and (with the reciprocal half) <em>Friends</em>.
 *
 * <p>String-targeted so DungeonTrain needs no compile-time dependency on the
 * playermob class; the method arg and {@code this} are referenced only via
 * vanilla supertypes. Server-side by construction: a {@link ServerPlayer}
 * target only exists on the logical server.</p>
 */
@Mixin(targets = "games.brennan.playermob.entity.PlayerMobEntity")
public abstract class PlayerMobGiveItemMixin {

    @Inject(method = "giveItemTo", at = @At("RETURN"))
    private void dungeontrain$onGiveItemTo(LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        if (!(target instanceof ServerPlayer player)) return;
        Entity self = (Entity) (Object) this;
        PlayerMobSocialTracker.recordMobGift(player, self.getUUID());
    }
}
