package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.CometPacket;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.worldgen.SpheresComets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * One comet in flight over the spheres band: a point the server flies on a fixed line that explodes where it meets
 * a carriage or a block. Immutable — each tick {@link #step} hands back the comet's next state, or empty once it has
 * hit something, burnt out or fallen well below the train.
 *
 * <p>Nothing of it exists in the world. Players within {@value #VIEW} blocks are told when it starts and when it is
 * gone ({@link CometPacket}), and their client flies and draws it (the block and its tail) from that alone, so a
 * comet costs two packets rather than an entity and a particle stream every tick, and can never be left behind.</p>
 */
record SpheresComet(int id, Vec3 pos, Vec3 velocity, int age) {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Tag on the {@code block_display} comets were drawn with before they were client-drawn; any left is removed. */
    static final String TAG = "dungeontrain_comet";
    /** Blocks per tick. */
    static final double SPEED = 1.6;
    static final int MAX_AGE_TICKS = 240;
    static final float EXPLOSION_POWER = 3.0F;
    private static final double CARRIAGE_INFLATE = 0.5;
    /** How far from the train a comet starts: along the track ahead, up, and to the side. */
    private static final double AHEAD_MIN = 40.0, AHEAD_SPAN = 50.0;
    private static final double UP_MIN = 60.0, UP_SPAN = 40.0;
    private static final double SIDE_SPAN = 50.0;
    /** Players this close (blocks) to a comet's start are told about it. */
    static final double VIEW = 256.0;
    /** Carriages this close (blocks, horizontally) to the watching player are the targets. */
    private static final double FOCUS_REACH = 24.0;
    /** How far off the train a near miss passes. */
    private static final double MISS_MIN = 15.0, MISS_SPAN = 25.0;
    /** Share of near misses that come down ahead of the train, so some are seen landing before it gets there. */
    private static final double MISS_AHEAD_CHANCE = 1.0 / 3.0;
    /** How far along the track ahead those cross deck height (they fall on backwards ~10-15 blocks after). */
    private static final double MISS_AHEAD_MIN = 20.0, MISS_AHEAD_SPAN = 25.0;
    /** How far out from the carriage's side they pass, so they clear the carriages ahead. */
    private static final double MISS_AHEAD_SIDE_MIN = 5.0, MISS_AHEAD_SIDE_SPAN = 7.0;

    /**
     * Launch a comet at {@code train}: aimed into a carriage near {@code focus} (leading it by the train's
     * velocity), or on a near-miss line: {@value #MISS_MIN}..{@code MISS_MIN + MISS_SPAN} blocks beside or over it,
     * or down beside the track ahead of it. Null when there is no train.
     */
    @Nullable
    static SpheresComet launch(ServerLevel level, List<Trains.Carriage> train, Vec3 focus,
                               SpheresCometEvents.Aim aim, int id, RandomSource random) {
        if (train.isEmpty()) return null;
        Trains.Carriage carriage = pick(train, focus, random);
        AABBdc bb = carriage.ship().worldAABB();
        Vec3 trainVel = perTick(carriage);
        double dirX = trainVel.x < -1.0E-3 ? -1.0 : 1.0;

        Vec3 target = new Vec3(
                bb.minX() + random.nextDouble() * (bb.maxX() - bb.minX()),
                bb.maxY() - 0.5,
                bb.minZ() + random.nextDouble() * (bb.maxZ() - bb.minZ()));
        boolean ahead = aim == SpheresCometEvents.Aim.MISS_AHEAD
                || (aim == SpheresCometEvents.Aim.MISS && random.nextDouble() < MISS_AHEAD_CHANCE);
        if (ahead) {
            target = aheadMiss(bb, target, dirX, random);
        } else if (aim != SpheresCometEvents.Aim.HIT) {
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

        tell(level, start, CometPacket.launched(id, start.x, start.y, start.z, velocity.x, velocity.y, velocity.z));
        return new SpheresComet(id, start, velocity, 0);
    }

    /** A point along the track ahead of {@code target}, out beside the train rather than on the carriages there. */
    private static Vec3 aheadMiss(AABBdc bb, Vec3 target, double dirX, RandomSource random) {
        double along = dirX * (MISS_AHEAD_MIN + random.nextDouble() * MISS_AHEAD_SPAN);
        double out = MISS_AHEAD_SIDE_MIN + random.nextDouble() * MISS_AHEAD_SIDE_SPAN;
        double z = random.nextBoolean() ? bb.maxZ() + out : bb.minZ() - out;
        return new Vec3(target.x + along, target.y, z);
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
     * Fly one tick. Empty when the comet is gone: it hit a carriage or a block (and exploded), burnt out, or fell
     * {@value SpheresComets#MISS_DROP_BLOCKS} blocks below the train; its watchers are told either way.
     */
    Optional<SpheresComet> step(ServerLevel level, List<Trains.Carriage> carriages) {
        Vec3 next = pos.add(velocity);
        Vec3 hit = firstHit(level, carriages, pos, next);
        if (hit != null) {
            LOGGER.debug("[DungeonTrain] Comet {} hit at {} after {} ticks", id, hit, age + 1);
            tell(level, pos, CometPacket.landed(id, hit.x, hit.y, hit.z));
            level.explode(null, hit.x, hit.y, hit.z, EXPLOSION_POWER, true, Level.ExplosionInteraction.TNT);
            return Optional.empty();
        }
        if (age + 1 >= MAX_AGE_TICKS || SpheresComets.fellPastTrain(next.y, lowestCarriageY(carriages))) {
            LOGGER.debug("[DungeonTrain] Comet {} burnt out at {}", id, next);
            tell(level, pos, CometPacket.ended(id));
            return Optional.empty();
        }
        return Optional.of(new SpheresComet(id, next, velocity, age + 1));
    }

    private static double lowestCarriageY(List<Trains.Carriage> carriages) {
        double low = Double.MAX_VALUE;
        for (Trains.Carriage c : carriages) low = Math.min(low, c.ship().worldAABB().minY());
        return low;
    }

    /** Send {@code packet} to every player within {@value #VIEW} blocks of {@code at}. */
    private static void tell(ServerLevel level, Vec3 at, CometPacket packet) {
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(at) <= VIEW * VIEW) PacketDistributor.sendToPlayer(player, packet);
        }
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

    /** The train's velocity in blocks per tick (target velocity is blocks per second). */
    private static Vec3 perTick(Trains.Carriage carriage) {
        var v = carriage.provider().getTargetVelocity();
        return new Vec3(v.x(), v.y(), v.z()).scale(1.0 / 20.0);
    }
}
