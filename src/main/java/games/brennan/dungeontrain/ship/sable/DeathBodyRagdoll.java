package games.brennan.dungeontrain.ship.sable;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.RagdollBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The body a player leaves where they died: a Sable: Ragdolls playerless ragdoll wearing their skin
 * and gear, spawned at the death position so the death scene's {@code spot} and {@code carriage}
 * shots have something to look at ({@code client.DeathCameraCycle} tracks it by sub-level id).
 *
 * <p>Visual only. Loot drops as vanilla drops it; the body holds nothing and nobody can grab it. It
 * goes when the player respawns or leaves ({@link DeadRiderGhostTracking#release}), when the server
 * stops, or after {@link #MAX_LIFETIME_TICKS}, whichever comes first, so a stuck scene can never
 * leave a body behind for good. Server thread only.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DeathBodyRagdoll {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A body is released no later than this after death (15 minutes); the mod also expires it itself then. */
    static final int MAX_LIFETIME_TICKS = 15 * 60 * 20;
    /** The sweep for over-age bodies runs this often. */
    private static final int SWEEP_INTERVAL_TICKS = 100;

    private record Body(UUID id, long spawnedAt) {}

    /** Live bodies by the dead player's UUID. */
    private static final Map<UUID, Body> BODIES = new HashMap<>();
    private static long serverTicks;

    private DeathBodyRagdoll() {}

    /**
     * Leave a body where {@code player} just died. Returns its root sub-level id, or null for a void
     * death (nothing to look at), when Sable: Ragdolls is absent, or when the spawn was refused.
     * Replaces any earlier body of the same player.
     */
    @Nullable
    public static UUID spawn(ServerPlayer player, boolean voidDeath) {
        release(player);
        if (voidDeath || !RagdollBridge.isPresent()) return null;
        Vec3 velocity = player.getDeltaMovement().scale(20.0);
        UUID id = RagdollBridge.spawnBody(player.serverLevel(), player.position(), player.getYRot(),
            player.getGameProfile(), velocity, player, MAX_LIFETIME_TICKS);
        if (id == null) {
            LOGGER.info("[DungeonTrain] No death body for {} (Sable: Ragdolls declined)", player.getGameProfile().getName());
            return null;
        }
        BODIES.put(player.getUUID(), new Body(id, serverTicks));
        LOGGER.info("[DungeonTrain] Death body {} for {} at {}", id, player.getGameProfile().getName(), player.position());
        return id;
    }

    /** Remove {@code player}'s body, if any (respawned or left). */
    public static void release(ServerPlayer player) {
        Body body = BODIES.remove(player.getUUID());
        if (body != null) RagdollBridge.release(body.id());
    }

    /** The live body of {@code player}, or null. */
    @Nullable
    public static UUID bodyOf(ServerPlayer player) {
        Body body = BODIES.get(player.getUUID());
        return body == null ? null : body.id();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        serverTicks++;
        if (serverTicks % SWEEP_INTERVAL_TICKS != 0 || BODIES.isEmpty()) return;
        for (Iterator<Body> it = BODIES.values().iterator(); it.hasNext(); ) {
            Body body = it.next();
            if (serverTicks - body.spawnedAt() < MAX_LIFETIME_TICKS) continue;
            it.remove();
            RagdollBridge.release(body.id());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        BODIES.clear();
        RagdollBridge.clear();
    }
}
