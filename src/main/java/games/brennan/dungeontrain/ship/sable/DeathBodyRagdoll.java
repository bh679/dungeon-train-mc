package games.brennan.dungeontrain.ship.sable;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import games.brennan.dungeontrain.compat.RagdollBridge;
import games.brennan.dungeontrain.event.RecentExplosions;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
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

    /** A throw waits this many server ticks at most for the body's physics to come up. */
    static final int THROW_ATTEMPTS = 10;

    private record Body(UUID id, long spawnedAt) {}

    /** A throw still to give a just-spawned body: the ragdoll parts that existed before it, and the velocity. */
    private record Throw(ServerLevel level, Set<UUID> partsBefore, Vec3 velocity, int attemptsLeft) {}

    /** Live bodies by the dead player's UUID. */
    private static final Map<UUID, Body> BODIES = new HashMap<>();
    /** Throws not yet applied, by body id. Sable makes a ragdoll's physics a tick or so after the spawn call. */
    private static final Map<UUID, Throw> THROWS = new HashMap<>();
    private static long serverTicks;

    private DeathBodyRagdoll() {}

    /**
     * Leave a body where {@code player} just died. Returns its root sub-level id, or null for a void
     * death (nothing to look at), when Sable: Ragdolls is absent, or when the spawn was refused.
     * Replaces any earlier body of the same player.
     */
    @Nullable
    public static UUID spawn(ServerPlayer player, DamageSource source, boolean voidDeath) {
        release(player);
        if (voidDeath || !RagdollBridge.isPresent()) return null;
        Vec3 thrown = throwFor(player, source);
        Vec3 velocity = player.getDeltaMovement().scale(20.0);
        Set<UUID> partsBefore = thrown.lengthSqr() > 0 ? RagdollBridge.ragdollParts(player.serverLevel()) : Set.of();
        UUID id = RagdollBridge.spawnBody(player.serverLevel(), player.position(), player.getYRot(),
            player.getGameProfile(), velocity, player, MAX_LIFETIME_TICKS);
        if (id == null) {
            LOGGER.info("[DungeonTrain] No death body for {} (Sable: Ragdolls declined)", player.getGameProfile().getName());
            return null;
        }
        BODIES.put(player.getUUID(), new Body(id, serverTicks));
        if (thrown.lengthSqr() > 0) THROWS.put(id, new Throw(player.serverLevel(), partsBefore, thrown, THROW_ATTEMPTS));
        LOGGER.info("[DungeonTrain] Death body {} for {} at {} thrown {}", id, player.getGameProfile().getName(), player.position(), thrown);
        return id;
    }

    /**
     * The throw what killed {@code player} gives the body (blocks per second): away from a blast,
     * along a projectile's flight, nothing for any other death. A blast is checked first, since a
     * fireball or a firework is both. On a carriage the part across the track is cut short
     * ({@link DeathKnockback#keepAboard}) and the carriage's own velocity is added, so the body
     * stays on the train.
     */
    static Vec3 throwFor(ServerPlayer player, DamageSource source) {
        Vec3 thrown = rawThrow(player, source);
        if (thrown.lengthSqr() == 0) return thrown;
        SubLevel carriage = Sable.HELPER.getTrackingSubLevel(player);
        if (carriage == null || carriage.isRemoved()) return thrown;
        // On a carriage: keep the body aboard, and give it the train's own speed so a body in the air
        // keeps pace instead of the train sliding out from under it.
        return DeathKnockback.keepAboard(thrown, carriageAxis(carriage)).add(carriageVelocity(carriage));
    }

    private static Vec3 rawThrow(ServerPlayer player, DamageSource source) {
        Vec3 body = player.position().add(0, player.getBbHeight() * 0.5, 0);
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            RecentExplosions.Blast blast = RecentExplosions.nearest(player.level().dimension(), body,
                player.level().getGameTime(), centre -> carriageToWorld(player, centre));
            if (blast != null) return DeathKnockback.explosion(blast.centre(), body, blast.radius());
            Vec3 from = source.getSourcePosition();
            return from == null ? Vec3.ZERO : DeathKnockback.explosion(from, body, DeathKnockback.DEFAULT_RADIUS);
        }
        if (source.getDirectEntity() instanceof Projectile projectile) {
            Entity shooter = source.getEntity();
            return DeathKnockback.projectile(projectile.getDeltaMovement(),
                shooter == null ? projectile.position() : shooter.position(), body);
        }
        return Vec3.ZERO;
    }

    /** The world direction of the track: the carriage's own X axis. */
    private static Vec3 carriageAxis(SubLevel carriage) {
        Vector3d origin = carriage.logicalPose().transformPosition(new Vector3d(0, 0, 0));
        Vector3d ahead = carriage.logicalPose().transformPosition(new Vector3d(1, 0, 0));
        return new Vec3(ahead.x - origin.x, ahead.y - origin.y, ahead.z - origin.z);
    }

    /** The carriage's linear velocity in the world (blocks per second); zero when its physics can't say. */
    private static Vec3 carriageVelocity(SubLevel carriage) {
        if (!(carriage instanceof ServerSubLevel server)) return Vec3.ZERO;
        RigidBodyHandle handle = RigidBodyHandle.of(server);
        if (handle == null || !handle.isValid()) return Vec3.ZERO;
        Vector3d v = handle.getLinearVelocity(new Vector3d());
        return new Vec3(v.x, v.y, v.z);
    }

    /** {@code pos} mapped out of the carriage plot {@code player} rides into the world; unchanged off a carriage. */
    private static Vec3 carriageToWorld(ServerPlayer player, Vec3 pos) {
        SubLevel carriage = Sable.HELPER.getTrackingSubLevel(player);
        if (carriage == null || carriage.isRemoved()) return pos;
        Vector3d world = carriage.logicalPose().transformPosition(new Vector3d(pos.x, pos.y, pos.z));
        return new Vec3(world.x, world.y, world.z);
    }

    /** Is a throw waiting for {@code bodyId}? True right after {@link #spawn} for an arrow or blast death. */
    public static boolean isThrown(@Nullable UUID bodyId) {
        return bodyId != null && THROWS.containsKey(bodyId);
    }

    /** Remove {@code player}'s body, if any (respawned or left). */
    public static void release(ServerPlayer player) {
        Body body = BODIES.remove(player.getUUID());
        if (body == null) return;
        THROWS.remove(body.id());
        RagdollBridge.release(body.id());
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
        applyThrows();
        if (serverTicks % SWEEP_INTERVAL_TICKS != 0 || BODIES.isEmpty()) return;
        for (Iterator<Body> it = BODIES.values().iterator(); it.hasNext(); ) {
            Body body = it.next();
            if (serverTicks - body.spawnedAt() < MAX_LIFETIME_TICKS) continue;
            it.remove();
            RagdollBridge.release(body.id());
        }
    }

    /** Give each waiting body its throw once its physics is up; give up after {@link #THROW_ATTEMPTS} ticks. */
    private static void applyThrows() {
        if (THROWS.isEmpty()) return;
        Map<UUID, Throw> next = new HashMap<>();
        for (Map.Entry<UUID, Throw> e : THROWS.entrySet()) {
            Throw t = e.getValue();
            int took = RagdollBridge.throwBody(t.level(), e.getKey(), t.partsBefore(), t.velocity());
            if (took == 0 && t.attemptsLeft() > 1) {
                next.put(e.getKey(), new Throw(t.level(), t.partsBefore(), t.velocity(), t.attemptsLeft() - 1));
            } else if (took <= 0) {
                LOGGER.info("[DungeonTrain] Death body {} could not be thrown (result {})", e.getKey(), took);
            }
        }
        THROWS.clear();
        THROWS.putAll(next);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        BODIES.clear();
        THROWS.clear();
        RagdollBridge.clear();
    }
}
