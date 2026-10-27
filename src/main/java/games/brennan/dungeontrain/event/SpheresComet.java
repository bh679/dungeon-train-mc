package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One comet in flight over the spheres band: a full-bright magma-block display the server flies a fixed line,
 * trailing a long tail of fire, smoke and glowing sparks, that explodes where it meets a carriage or a block. Immutable — each tick
 * {@link #step} hands back the comet's next state, or empty once it has hit something or burnt out.
 *
 * <p>A vanilla {@code block_display}, not a registered entity: nothing to render client-side, and its
 * {@code teleport_duration} smooths the per-tick moves. Tagged {@link #TAG} so one left in a save by a server
 * stopped mid-flight can be thrown away when it loads ({@link SpheresCometEvents}).</p>
 */
record SpheresComet(UUID displayId, Vec3 pos, Vec3 velocity, int age) {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String TAG = "dungeontrain_comet";
    /** Blocks per tick. */
    static final double SPEED = 1.6;
    static final int MAX_AGE_TICKS = 240;
    static final float EXPLOSION_POWER = 3.0F;
    private static final float SCALE = 1.6F;
    private static final int TELEPORT_DURATION = 2;
    private static final double CARRIAGE_INFLATE = 0.5;
    /** How far from the train a comet starts: along the track ahead, up, and to the side. */
    private static final double AHEAD_MIN = 40.0, AHEAD_SPAN = 50.0;
    private static final double UP_MIN = 60.0, UP_SPAN = 40.0;
    private static final double SIDE_SPAN = 50.0;
    /** Players this close (blocks) see the tail. */
    private static final double TAIL_VIEW = 256.0;
    /** Points per tick the tail is laid at along the comet's step. */
    private static final int TAIL_STEPS = 4;
    /** Carriages this close (blocks, horizontally) to the watching player are the targets. */
    private static final double FOCUS_REACH = 24.0;
    /** How far off the train a near miss passes. */
    private static final double MISS_MIN = 15.0, MISS_SPAN = 25.0;

    /**
     * Launch a comet at {@code train}: aimed into a carriage near {@code focus} (leading it by the train's
     * velocity), or on a near-miss line {@value #MISS_MIN}..{@code MISS_MIN + MISS_SPAN} blocks off it. Null when
     * the display could not be added.
     */
    @Nullable
    static SpheresComet launch(ServerLevel level, List<Trains.Carriage> train, Vec3 focus, boolean aimed,
                               RandomSource random) {
        if (train.isEmpty()) return null;
        Trains.Carriage carriage = pick(train, focus, random);
        AABBdc bb = carriage.ship().worldAABB();
        Vec3 trainVel = perTick(carriage);
        double dirX = trainVel.x < -1.0E-3 ? -1.0 : 1.0;

        Vec3 target = new Vec3(
                bb.minX() + random.nextDouble() * (bb.maxX() - bb.minX()),
                bb.maxY() - 0.5,
                bb.minZ() + random.nextDouble() * (bb.maxZ() - bb.minZ()));
        if (!aimed) {
            double miss = MISS_MIN + random.nextDouble() * MISS_SPAN;
            target = target.add(0.0, random.nextBoolean() ? miss * 0.4 : 0.0, random.nextBoolean() ? miss : -miss);
        }
        Vec3 offset = new Vec3(
                dirX * (AHEAD_MIN + random.nextDouble() * AHEAD_SPAN),
                UP_MIN + random.nextDouble() * UP_SPAN,
                (random.nextDouble() * 2.0 - 1.0) * SIDE_SPAN);
        double ticks = offset.length() / SPEED;
        Vec3 meet = target.add(trainVel.scale(ticks));
        Vec3 start = meet.add(offset);
        Vec3 velocity = offset.scale(-1.0 / ticks);

        Entity display = spawnDisplay(level, start);
        if (display == null) return null;
        level.playSound(null, start.x, start.y, start.z, SoundEvents.GHAST_SHOOT, SoundSource.AMBIENT, 8.0F, 0.5F);
        return new SpheresComet(display.getUUID(), start, velocity, 0);
    }

    /** A random carriage within {@value #FOCUS_REACH} blocks of {@code focus}, else the nearest one. */
    private static Trains.Carriage pick(List<Trains.Carriage> train, Vec3 focus, RandomSource random) {
        List<Trains.Carriage> near = new java.util.ArrayList<>();
        Trains.Carriage nearest = train.get(0);
        double nearestD = Double.MAX_VALUE;
        for (Trains.Carriage c : train) {
            AABBdc bb = c.ship().worldAABB();
            double dx = (bb.minX() + bb.maxX()) / 2 - focus.x, dz = (bb.minZ() + bb.maxZ()) / 2 - focus.z;
            double d = dx * dx + dz * dz;
            if (d <= FOCUS_REACH * FOCUS_REACH) near.add(c);
            if (d < nearestD) {
                nearestD = d;
                nearest = c;
            }
        }
        return near.isEmpty() ? nearest : near.get(random.nextInt(near.size()));
    }

    /**
     * Fly one tick. Empty when the comet is gone: it hit a carriage or a block (and exploded), burnt out, lost its
     * display, or reached ground the server has not loaded.
     */
    Optional<SpheresComet> step(ServerLevel level, List<Trains.Carriage> carriages) {
        Entity display = level.getEntity(displayId);
        if (display == null) {
            LOGGER.debug("[DungeonTrain] Comet {} lost its display at {}", displayId, pos);
            return Optional.empty();
        }
        Vec3 next = pos.add(velocity);
        Vec3 hit = firstHit(level, carriages, pos, next);
        if (hit != null) {
            LOGGER.debug("[DungeonTrain] Comet {} hit at {} after {} ticks", displayId, hit, age + 1);
            display.discard();
            level.explode(null, hit.x, hit.y, hit.z, EXPLOSION_POWER, true, Level.ExplosionInteraction.TNT);
            return Optional.empty();
        }
        if (age + 1 >= MAX_AGE_TICKS) {
            LOGGER.debug("[DungeonTrain] Comet {} burnt out at {}", displayId, next);
            display.discard();
            return Optional.empty();
        }
        display.setPos(next.x, next.y, next.z);
        trail(level, pos, next, velocity);
        return Optional.of(new SpheresComet(displayId, next, velocity, age + 1));
    }

    /** Where the segment {@code from → to} first meets a carriage or a loaded world block, or null. */
    @Nullable
    private static Vec3 firstHit(ServerLevel level, List<Trains.Carriage> carriages, Vec3 from, Vec3 to) {
        Vec3 best = null;
        double bestDist = Double.MAX_VALUE;
        for (Trains.Carriage c : carriages) {
            AABBdc bb = c.ship().worldAABB();
            AABB box = new AABB(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ()).inflate(CARRIAGE_INFLATE);
            Optional<Vec3> at = box.contains(from) ? Optional.of(from) : box.clip(from, to);
            if (at.isPresent() && from.distanceToSqr(at.get()) < bestDist) {
                best = at.get();
                bestDist = from.distanceToSqr(best);
            }
        }
        if (best != null) return best;
        // Never sync-load a chunk to find out: a comet over unloaded ground just flies on until it burns out.
        if (!level.hasChunkAt(BlockPos.containing(from)) || !level.hasChunkAt(BlockPos.containing(to))) return null;
        BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        return block.getType() == HitResult.Type.BLOCK ? block.getLocation() : null;
    }

    /**
     * The tail, laid along this tick's whole step so it reads as one unbroken streak: flames blown back off the head,
     * dark smoke behind them, and glowing end-rod sparks that hang in the sky for a few seconds, so a long bright
     * tail traces the comet's path. Sent long-distance to each player within {@value #TAIL_VIEW} blocks; the
     * ordinary broadcast stops at 32, which left only the bare block visible from the train.
     */
    private static void trail(ServerLevel level, Vec3 from, Vec3 to, Vec3 velocity) {
        Vec3 back = velocity.normalize().scale(-1.0);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(to) > TAIL_VIEW * TAIL_VIEW) continue;
            for (int i = 0; i < TAIL_STEPS; i++) {
                Vec3 at = from.lerp(to, (i + 1.0) / TAIL_STEPS);
                for (int f = 0; f < 2; f++) {
                    Vec3 dir = back.add(jitter(level.random, 0.25));
                    level.sendParticles(player, ParticleTypes.FLAME, true, at.x, at.y, at.z, 0, dir.x, dir.y, dir.z, 0.25);
                }
                level.sendParticles(player, ParticleTypes.LARGE_SMOKE, true, at.x, at.y, at.z, 1, 0.3, 0.3, 0.3, 0.01);
                Vec3 drift = back.add(jitter(level.random, 0.6));
                level.sendParticles(player, ParticleTypes.END_ROD, true, at.x, at.y, at.z, 0, drift.x, drift.y, drift.z, 0.04);
            }
            if (level.random.nextBoolean()) {
                level.sendParticles(player, ParticleTypes.LAVA, true, to.x, to.y, to.z, 1, 0.3, 0.3, 0.3, 0.0);
            }
        }
    }

    private static Vec3 jitter(RandomSource random, double spread) {
        return new Vec3((random.nextDouble() - 0.5) * spread, (random.nextDouble() - 0.5) * spread,
                (random.nextDouble() - 0.5) * spread);
    }

    /** The train's velocity in blocks per tick (target velocity is blocks per second). */
    private static Vec3 perTick(Trains.Carriage carriage) {
        var v = carriage.provider().getTargetVelocity();
        return new Vec3(v.x(), v.y(), v.z()).scale(1.0 / 20.0);
    }

    @Nullable
    private static Entity spawnDisplay(ServerLevel level, Vec3 at) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:block_display");
        CompoundTag state = new CompoundTag();
        state.putString("Name", "minecraft:magma_block");
        tag.put("block_state", state);
        CompoundTag transformation = new CompoundTag();
        transformation.put("left_rotation", floats(0F, 0F, 0F, 1F));
        transformation.put("right_rotation", floats(0F, 0F, 0F, 1F));
        transformation.put("translation", floats(-SCALE / 2, -SCALE / 2, -SCALE / 2));
        transformation.put("scale", floats(SCALE, SCALE, SCALE));
        tag.put("transformation", transformation);
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("sky", 15);
        brightness.putInt("block", 15);
        tag.put("brightness", brightness);
        tag.putInt("teleport_duration", TELEPORT_DURATION);
        tag.putFloat("view_range", 4.0F);
        ListTag tags = new ListTag();
        tags.add(StringTag.valueOf(TAG));
        tag.put("Tags", tags);
        Entity display = EntityType.loadEntityRecursive(tag, level, e -> {
            e.moveTo(at.x, at.y, at.z, 0F, 0F);
            return e;
        });
        if (display == null || !level.addFreshEntity(display)) return null;
        return display;
    }

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float f : values) list.add(FloatTag.valueOf(f));
        return list;
    }
}
