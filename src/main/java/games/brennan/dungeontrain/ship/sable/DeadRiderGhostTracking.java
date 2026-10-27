package games.brennan.dungeontrain.ship.sable;

import games.brennan.dungeontrain.mixin.ChunkMapDeadRiderAccessor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Bookkeeping for {@code mixin.ChunkMapDeadRiderMixin}: which dead players still hold their chunk map
 * registration, and the deferred vanilla clean-up for them (run from {@code mixin.PlayerListDeadRiderMixin}).
 */
public final class DeadRiderGhostTracking {

    private static final Set<ServerPlayer> GHOSTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<ServerPlayer> RELEASING = Collections.newSetFromMap(new WeakHashMap<>());

    private DeadRiderGhostTracking() {}

    /** Called as vanilla removes the player: true (and remembered) when the removal should wait for respawn. */
    public static boolean defer(ServerPlayer player) {
        if (RELEASING.contains(player)) return false;
        boolean ghost = player.getRemovalReason() == Entity.RemovalReason.KILLED
            && !player.hasDisconnected()
            && DeadRiderAnchors.deadIn(player.serverLevel()).contains(player);
        if (ghost) GHOSTS.add(player);
        return ghost;
    }

    /** True while this player's removal is deferred (set by {@link #defer} in the same call). */
    public static boolean isDeferred(ServerPlayer player) {
        return GHOSTS.contains(player) && !RELEASING.contains(player);
    }

    /** What vanilla would have done at death: drop the player from the chunk map and every tracker. */
    public static void release(ServerPlayer player) {
        DeadRiderServerCarry.release(player);
        if (!GHOSTS.remove(player)) return;
        RELEASING.add(player);
        try {
            ((ChunkMapDeadRiderAccessor) player.serverLevel().getChunkSource().chunkMap).dungeontrain$removeEntity(player);
        } finally {
            RELEASING.remove(player);
        }
    }
}
