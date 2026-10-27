package games.brennan.dungeontrain.ship.sable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Players who died in a level and are still on the death screen, treated as riders standing where
 * they fell.
 *
 * <p>Vanilla removes a dead {@link ServerPlayer} at death tick 20 ({@code LivingEntity.tickDeath}
 * → {@code remove(KILLED)}), which drops it from {@link ServerLevel#players()}. Without this helper
 * the train then has no near player (generation ahead stops, holds release) and Sable's tracking
 * loop sends stop-tracking for every carriage to that client, so the whole train vanishes from the
 * dead player's view about a second after death.</p>
 *
 * <p>The server's player list keeps the dead entity, connection intact, until
 * {@code PlayerList.respawn} swaps in the new entity or the player logs out. A removed entity is
 * never ticked, so its position is frozen at the death spot in world space. That entity is the
 * anchor: nothing is stored here and nothing needs cleaning up. Callers that keep the train alive
 * read {@link #withDead(ServerLevel)} instead of {@code level.players()}; the Sable tracking mixin
 * reads the same list plus {@link #deadByUUID(ServerLevel, UUID)}.</p>
 */
public final class DeadRiderAnchors {

    private DeadRiderAnchors() {}

    /** The rule, factored out so it can be unit-tested without a server. */
    static boolean isDeadAnchor(boolean inThisLevel, boolean removed,
                                @Nullable Entity.RemovalReason reason, boolean deadOrDying) {
        return inThisLevel && removed && reason == Entity.RemovalReason.KILLED && deadOrDying;
    }

    private static boolean isDeadAnchor(ServerLevel level, ServerPlayer player) {
        return isDeadAnchor(player.level() == level, player.isRemoved(),
            player.getRemovalReason(), player.isDeadOrDying());
    }

    /** Players who died in {@code level} and have not respawned or logged out yet. */
    public static List<ServerPlayer> deadIn(ServerLevel level) {
        List<ServerPlayer> dead = null;
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (!isDeadAnchor(level, player)) continue;
            if (dead == null) dead = new ArrayList<>(2);
            dead.add(player);
        }
        return dead == null ? List.of() : List.copyOf(dead);
    }

    /**
     * {@code level.players()} plus {@link #deadIn(ServerLevel)}. Returns {@code level.players()}
     * itself when nobody is dead, so the per-tick hot path allocates nothing.
     */
    public static List<ServerPlayer> withDead(ServerLevel level) {
        List<ServerPlayer> live = level.players();
        List<ServerPlayer> dead = deadIn(level);
        return dead.isEmpty() ? live : merged(live, dead);
    }

    /** Pure merge of the two lists: {@code live} itself when {@code dead} is empty. */
    public static <T> List<T> merged(List<T> live, List<T> dead) {
        if (dead.isEmpty()) return live;
        List<T> all = new ArrayList<>(live.size() + dead.size());
        all.addAll(live);
        all.addAll(dead);
        return List.copyOf(all);
    }

    /**
     * Where the train's keep-alive should measure this player from: a dead rider's death spot
     * (their body rides on for the trackers, but the line carries on as if they stood where they
     * fell), else the player's own position.
     */
    public static Vec3 anchorPos(ServerPlayer player) {
        Vec3 spot = DeadRiderServerCarry.deathSpot(player);
        return spot != null ? spot : player.position();
    }

    /** The dead-but-connected player with this id in {@code level}, or null. */
    @Nullable
    public static ServerPlayer deadByUUID(ServerLevel level, UUID id) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
        return player != null && isDeadAnchor(level, player) ? player : null;
    }
}
