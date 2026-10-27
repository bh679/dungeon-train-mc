package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import games.brennan.dungeontrain.ship.sable.DeadRiderAnchors;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.UUID;

/**
 * Keeps a dead player tracking the train from where they died until they respawn.
 *
 * <p>Sable's tracking loop reads {@code level.players()} to decide who tracks each sub-level and
 * {@code level.getPlayerByUUID} to re-check existing trackers. Vanilla removes a dead
 * {@link ServerPlayer} from both at death tick 20, so one second after death Sable sends
 * stop-tracking for every carriage to that client and the whole train disappears from the death
 * screen. Both reads are widened here with {@link DeadRiderAnchors}: the dead, still-connected
 * player entity, frozen at its world-space death position, keeps tracking whatever is in range of
 * that spot exactly as a living player standing there would.</p>
 *
 * <p>Bytecode-verified against {@code sable-2.0.5+mc1.21.1}: {@code ServerLevel.players()} is
 * called once in {@code collectPlayers} and once in {@code tick}; {@code getPlayerByUUID} is called
 * twice in {@code tick} and three times in {@code sendMovementUpdates} (without those the dead player stays tracked but gets no movement packets, and the train
 * freezes on their screen). <b>Re-verify on any {@code sable_version} bump.</b> Left {@code required}
 * so a Sable change fails loudly instead of silently bringing the vanish back.</p>
 */
@Mixin(value = SubLevelTrackingSystem.class, remap = false)
public abstract class SubLevelTrackingDeadRiderMixin {

    @WrapOperation(
        method = {"collectPlayers", "tick"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;"
        )
    )
    private List<ServerPlayer> dungeontrain$playersWithDead(ServerLevel level,
                                                            Operation<List<ServerPlayer>> original) {
        List<ServerPlayer> live = original.call(level);
        return DeadRiderAnchors.merged(live, DeadRiderAnchors.deadIn(level));
    }

    @WrapOperation(
        method = {"tick", "sendMovementUpdates"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;"
        )
    )
    private Player dungeontrain$deadPlayerByUUID(ServerLevel level, UUID id, Operation<Player> original) {
        Player player = original.call(level, id);
        return player != null ? player : DeadRiderAnchors.deadByUUID(level, id);
    }
}
