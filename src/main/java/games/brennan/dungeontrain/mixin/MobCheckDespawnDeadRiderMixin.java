package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.ship.sable.DeadRiderAnchors;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Mobs stay on the train while their rider is dead. Vanilla's despawn check asks for the nearest
 * player; a dead player has left the level's list, so with nobody else aboard every hostile beyond
 * 128 blocks is discarded at once and the rest at random. The dead, still-connected player
 * ({@link DeadRiderAnchors}) stands in for that lookup, exactly as the train's own sweeps do.
 */
@Mixin(Mob.class)
public abstract class MobCheckDespawnDeadRiderMixin {

    @WrapOperation(
        method = "checkDespawn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getNearestPlayer(Lnet/minecraft/world/entity/Entity;D)Lnet/minecraft/world/entity/player/Player;"
        )
    )
    private Player dungeontrain$deadRiderAsNearest(Level level, Entity self, double distance, Operation<Player> original) {
        Player nearest = original.call(level, self, distance);
        if (nearest != null || !(level instanceof ServerLevel server)) return nearest;
        ServerPlayer best = null;
        double bestDist = Double.MAX_VALUE;
        for (ServerPlayer dead : DeadRiderAnchors.deadIn(server)) {
            double d = dead.distanceToSqr(self);
            if (d < bestDist) {
                bestDist = d;
                best = dead;
            }
        }
        return best;
    }
}
