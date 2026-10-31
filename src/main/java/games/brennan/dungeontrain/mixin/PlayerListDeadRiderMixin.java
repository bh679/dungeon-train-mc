package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.ship.sable.DeadRiderGhostTracking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Runs the chunk-map clean-up {@link ChunkMapDeadRiderMixin} deferred, when the dead player respawns or leaves. */
@Mixin(PlayerList.class)
public abstract class PlayerListDeadRiderMixin {

    @Inject(method = "respawn", at = @At("HEAD"))
    private void dungeontrain$releaseGhostOnRespawn(ServerPlayer player, boolean keepInventory, Entity.RemovalReason reason,
                                                   CallbackInfoReturnable<ServerPlayer> cir) {
        DeadRiderGhostTracking.release(player);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void dungeontrain$releaseGhostOnLogout(ServerPlayer player, CallbackInfo ci) {
        DeadRiderGhostTracking.release(player);
    }
}
