package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.network.udp.SableUDPServer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import games.brennan.dungeontrain.ship.sable.RecentFullSyncTracker;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps a freshly full-synced player's movement snapshots behind the full sync, so the client
 * never gets a MOVE for a sub-level it hasn't registered yet.
 *
 * <p>Sable sends the full sync ({@code sendFullSync}) over the ordered connection, and the same
 * tick's MOVE snapshot ({@code sendMovementUpdates}) over its UDP pipeline when
 * {@code SableUDPServer.isConnectedTo(player)} — in singleplayer that pipeline drops the packet
 * straight onto the client's {@code NETWORK_EVENT_LOOP}, ahead of the full sync still in the
 * connection's queue. The client then logs "Received a sub-level movement packet for a
 * non-existent sub-level". Dungeon Train lost that race on about half of all appended carriage
 * groups. Reporting "not connected" for {@link RecentFullSyncTracker#ORDERED_SNAPSHOT_WINDOW_TICKS}
 * after a full sync makes Sable use its own non-UDP fallback — a bundle on the same ordered
 * connection, so it is handled after the full sync. Poses and timing are untouched.</p>
 *
 * <p>Bytecode-verified against {@code sable-2.0.5+mc1.21.1}: {@code sendFullSync} is the only
 * start-tracking send, and {@code isConnectedTo} is called once, in {@code sendMovementUpdates}.
 * <b>Re-verify on any {@code sable_version} bump.</b> Left {@code required} so a Sable change
 * fails loudly instead of silently bringing the error back.</p>
 */
@Mixin(value = SubLevelTrackingSystem.class, remap = false)
public abstract class SubLevelTrackingOrderedSnapshotMixin {

    @Unique
    private static final Logger dungeontrain$LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter.subtrack");

    @Inject(method = "sendFullSync", at = @At("HEAD"))
    private void dungeontrain$recordFullSync(ServerPlayer player, ServerSubLevel subLevel,
                                             CustomPacketPayload extra, CallbackInfo ci) {
        long tick = player.server.getTickCount();
        RecentFullSyncTracker.recordFullSync(player.getUUID(), tick);
        dungeontrain$LOGGER.debug("[subTrack] fullSync subLevel={} player={} tick={}",
            subLevel.getUniqueId(), player.getGameProfile().getName(), tick);
    }

    @WrapOperation(
        method = "sendMovementUpdates",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/network/udp/SableUDPServer;isConnectedTo(Lnet/minecraft/server/level/ServerPlayer;)Z"
        )
    )
    private boolean dungeontrain$orderedAfterFullSync(SableUDPServer udp, ServerPlayer player,
                                                      Operation<Boolean> original) {
        if (RecentFullSyncTracker.needsOrderedSnapshots(player.getUUID(), player.server.getTickCount())) {
            return false;
        }
        return original.call(udp, player);
    }
}
