package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.event.PlayerJoinEvents;
import games.brennan.dungeontrain.event.PlayerJoinEvents.SpawnPlacement;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla's {@link Player#fudgeSpawnLocation(ServerLevel)} for
 * first-time players with our pre-computed bootstrap placement. Vanilla
 * fudge searches a {@code spawnRadius=10}-block square around
 * {@code sharedSpawnPos} for a {@code MOTION_BLOCKING}-valid surface — a
 * heuristic that disagrees with our {@code findGroundY} (which descends
 * through leaves to find the trunk top, rejected by
 * {@code isSafePlayerPos} so the player doesn't end up inside the canopy).
 * The disagreement caused first-time logins to land 5–10 blocks off the
 * cached placement, leaving a visible jump when the {@code PlayerJoinEvents}
 * retry teleport snapped them back to the cache.
 *
 * <p>With this mixin, fudge directly applies the cached placement and
 * cancels — the {@code ClientboundLoginPacket} sent immediately after
 * carries the cache's position and rotation, so the client renders the
 * final spawn from the very first frame.</p>
 */
@Mixin(Player.class)
public abstract class PlayerFudgeSpawnMixin {

    @Inject(method = "fudgeSpawnLocation", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$useCachedPlacement(ServerLevel level, CallbackInfo ci) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        SpawnPlacement placement = PlayerJoinEvents.getBootstrapPlacement();
        if (placement == null) return;

        // moveTo sets position + rotation; the subsequent ClientboundLoginPacket
        // serialises these values directly so the client never sees an
        // intermediate vanilla-fudged position.
        player.moveTo(placement.x(), placement.y(), placement.z(), placement.yaw(), placement.pitch());
        ci.cancel();
    }
}
