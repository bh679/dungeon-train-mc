package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.ship.sable.DeadRiderAnchors;
import games.brennan.dungeontrain.ship.sable.DeadRiderGhostTracking;
import games.brennan.dungeontrain.ship.sable.DeadRiderServerCarry;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectCollection;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * A dead player keeps seeing the world until they respawn. Vanilla removes the dead player entity
 * at death tick 20 and {@code ChunkMap.removeEntity} then drops that client from the player map
 * (forgetting every chunk) and from every entity tracker (removing every mob from their screen).
 * For a dead, still-connected player ({@link DeadRiderAnchors}) both steps are deferred to
 * {@link DeadRiderGhostTracking#release}, which runs when they respawn or log out, so the train's
 * mobs stay in view and the chunks around the body stay loaded, as if they were standing there.
 * Newly tracked entities also consider the dead player ({@code level.players()} widened in the
 * tracker ticks), so a mob walking into range still appears on their screen.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapDeadRiderMixin {

    @WrapOperation(
        method = "removeEntity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;updatePlayerStatus(Lnet/minecraft/server/level/ServerPlayer;Z)V")
    )
    private void dungeontrain$keepDeadPlayerStatus(ChunkMap map, ServerPlayer player, boolean track, Operation<Void> original) {
        if (DeadRiderGhostTracking.defer(player)) return;
        original.call(map, player, track);
    }

    /** The tracker loop in {@code removeEntity} runs over this collection; empty it for a deferred player. */
    @WrapOperation(
        method = "removeEntity",
        at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;values()Lit/unimi/dsi/fastutil/objects/ObjectCollection;")
    )
    private ObjectCollection<?> dungeontrain$keepDeadPlayerInTrackers(Int2ObjectMap<?> map, Operation<ObjectCollection<?>> original, Entity entity) {
        if (entity instanceof ServerPlayer player && DeadRiderGhostTracking.isDeferred(player)) return ObjectLists.emptyList();
        return original.call(map);
    }

    /**
     * A dead rider's chunk view follows the body as the carriage carries it away, but the death
     * scene's cameras hang around the death spot. Widen the view so both stay sent to that client
     * (grown with the distance between them, capped), or the world under the camera is forgotten
     * about a minute after death.
     */
    @Inject(method = "getPlayerViewDistance", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$deadRiderSeesTheDeathSpot(ServerPlayer player, CallbackInfoReturnable<Integer> cir) {
        int extra = DeadRiderServerCarry.extraViewChunks(player);
        if (extra > 0) cir.setReturnValue(Math.min(DeadRiderServerCarry.MAX_VIEW_CHUNKS, cir.getReturnValue() + extra));
    }

    @WrapOperation(
        method = {"tick()V", "move"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;")
    )
    private List<ServerPlayer> dungeontrain$trackForDeadPlayers(ServerLevel level, Operation<List<ServerPlayer>> original) {
        return DeadRiderAnchors.merged(original.call(level), DeadRiderAnchors.deadIn(level));
    }
}
