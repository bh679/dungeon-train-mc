package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.event.PlayerJoinEvents;
import games.brennan.dungeontrain.event.PlayerJoinEvents.SpawnPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces vanilla's {@link ServerPlayer#adjustSpawnLocation(ServerLevel, BlockPos)}
 * (the 1.21.1 successor to {@code fudgeSpawnLocation}) for first-time
 * players with our pre-computed bootstrap placement.
 *
 * <p>Vanilla {@code adjustSpawnLocation} searches a {@code spawnRadius=10}
 * block square around {@code sharedSpawnPos} via
 * {@code PlayerRespawnLogic.getOverworldRespawnPos}, which uses the
 * {@code MOTION_BLOCKING} heightmap and stops on leaves — disagreeing with
 * our {@code findGroundY} (which descends through leaves to find the
 * trunk top, rejected by {@code isSafePlayerPos} so the player doesn't end
 * up inside the canopy). Diagnostic logs from 0.80.5 showed first-time
 * players landing 5–10 blocks off the cached placement.</p>
 *
 * <p>With this mixin, {@code adjustSpawnLocation} returns the cached
 * placement's BlockPos directly. The constructor's
 * {@code moveTo(adjustSpawnLocation(...).getBottomCenter(), 0F, 0F)} then
 * places the player at exactly cache (x.5, y, z.5) — the
 * {@code ClientboundLoginPacket} carries that position so the client
 * renders the final spawn from the very first frame. Rotation is set by
 * {@code PlayerJoinEvents}' first-tick no-op teleport.</p>
 */
@Mixin(ServerPlayer.class)
public abstract class PlayerFudgeSpawnMixin {

    @Inject(method = "adjustSpawnLocation", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$useCachedPlacement(
        ServerLevel level, BlockPos pos, CallbackInfoReturnable<BlockPos> cir
    ) {
        SpawnPlacement placement = PlayerJoinEvents.getBootstrapPlacement();
        if (placement == null) return;
        cir.setReturnValue(placement.blockPos());
    }
}
