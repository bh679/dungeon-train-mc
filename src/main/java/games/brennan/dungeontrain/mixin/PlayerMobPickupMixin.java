package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.advancement.PlayerMobSocialTracker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Observes the bundled playermob mod's
 * {@code PlayerMobEntity.tryPickUpFloorItem(ItemEntity)} — the passenger
 * collecting an item off the floor. When the picked-up item was thrown by a
 * player (its {@link ItemEntity#getOwner()} resolves to a {@link ServerPlayer}),
 * this counts as the player having <em>given</em> the mob an item, recorded
 * against the (player, mob) pair in {@link PlayerMobSocialTracker}. With the
 * reciprocal gift this unlocks <em>Friends</em>.
 *
 * <p>String-targeted so DungeonTrain needs no compile-time dependency on the
 * playermob class; the method arg and {@code this} are referenced only via
 * vanilla supertypes.</p>
 */
@Mixin(targets = "games.brennan.playermob.entity.PlayerMobEntity")
public abstract class PlayerMobPickupMixin {

    @Inject(method = "tryPickUpFloorItem", at = @At("RETURN"))
    private void dungeontrain$onPickUpFloorItem(ItemEntity item, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        if (!(item.getOwner() instanceof ServerPlayer player)) return;
        Entity self = (Entity) (Object) this;
        PlayerMobSocialTracker.recordPlayerGift(player, self.getUUID());
    }
}
