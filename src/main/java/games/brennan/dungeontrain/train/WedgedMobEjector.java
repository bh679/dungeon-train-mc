package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.event.NetherMobSpawner;
import games.brennan.dungeontrain.util.DtLogging;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Per-tick safety net: moves any mob stuck in a carriage floor out to the ground beside the
 * carriage. See {@link WedgedUnderFloor} for how one gets there and the rule that finds it.
 *
 * <p>Spawning into a carriage is refused at the source ({@code NetherMobSpawner},
 * {@code TrainSpawnGuard}); this catches the rest — mobs already wedged in an existing save, or one
 * that gets in some other way. If neither side of the carriage has room with ground close below it,
 * the mob is discarded, which is what happened to every one of them before 0.1397.0. A side spot over
 * a drop is never used: a wolf moved off a bridge that way fell to its death.</p>
 *
 * <p>Owned pets never go over the side. One whose owner is online is sent to the owner (onto the
 * deck, when they ride one); one whose owner is offline takes a safe side spot or stays put, and is
 * never discarded.</p>
 *
 * <p>A mob knocked off the train onto the tracks within {@link WedgedUnderFloor#ROOMS_NEAR_PLAYER}
 * rooms of a player is left there instead, and crushed half a heart per
 * {@link WedgedUnderFloor#TICKS_PER_HALF_HEART} ticks it spends under carriages, in small hits every
 * {@link WedgedUnderFloor#CRUSH_INTERVAL_TICKS}. Before 0.1570.0
 * it was discarded wherever the track was a bridge.</p>
 *
 * <p>A side whose chunk isn't loaded is never checked (that would sync-load it); if one was skipped,
 * the mob waits for a later tick instead of being discarded.</p>
 */
public final class WedgedMobEjector {

    private static final Logger LOGGER = LoggerFactory.getLogger(DtLogging.JITTER);

    /** One per level, so a mob that keeps getting back in logs once per window, not per move. */
    private static final Map<ServerLevel, EjectLogThrottle> LOG_THROTTLES = new WeakHashMap<>();

    /** Same, for a mob left waiting on an unloaded side; kept apart so it never hides a later move's line. */
    private static final Map<ServerLevel, EjectLogThrottle> WAIT_LOG_THROTTLES = new WeakHashMap<>();

    /** Per fallen mob: {last game tick it was under a carriage, ticks it has spent under them}. */
    private static final Map<Entity, long[]> CRUSH_TICKS = new WeakHashMap<>();

    private WedgedMobEjector() {}

    /** Run once per train per level tick. Players are never touched. */
    public static void eject(ServerLevel level, List<Trains.Carriage> train) {
        for (Trains.Carriage carriage : train) {
            AABBdc c = carriage.ship().worldAABB();
            if (c.maxX() <= c.minX() || c.maxY() <= c.minY() || c.maxZ() <= c.minZ()) continue;
            // Just the floor layer and a mob's height below it: anything wedged reaches into the floor.
            AABB floorBand = new AABB(c.minX(), c.minY() - 3.0, c.minZ(), c.maxX(), c.minY() + 1.0, c.maxZ());
            List<Entity> wedged = level.getEntitiesOfClass(Entity.class, floorBand,
                e -> !(e instanceof Player) && e.isAlive() && !e.isPassenger()
                    && WedgedUnderFloor.isWedged(e.getX(), e.getY(), e.getZ(), e.getEyeY(),
                        c.minX(), c.minY(), c.minZ(), c.maxX(), c.maxZ()));
            if (wedged.isEmpty()) continue;

            Vector3dc velocity = carriage.provider().getTargetVelocity();
            int roomLength = DungeonTrainWorldData.get(level.getServer().overworld()).dims().length();
            for (Entity e : wedged) {
                // Owned pets keep their own routing (to the owner, or waiting): never crushed.
                if (e instanceof LivingEntity living && CarriagePets.ownerOf(e) == null
                        && WedgedUnderFloor.fellOffTrain(e.tickCount) && nearPlayer(level, e, c, velocity, roomLength)) {
                    crush(level, living);
                    continue;
                }
                moveOut(level, e, c, velocity);
            }
        }
    }

    private static void moveOut(ServerLevel level, Entity e, AABBdc c, Vector3dc velocity) {
        UUID ownerId = CarriagePets.ownerOf(e);
        ServerPlayer owner = ownerId == null ? null : level.getServer().getPlayerList().getPlayer(ownerId);
        if (WedgedUnderFloor.route(ownerId != null, owner != null) == WedgedUnderFloor.Route.TO_OWNER) {
            CarriagePets.sendHome(e, owner, "a carriage floor");
            return;
        }
        double halfWidth = e.getBbWidth() / 2.0;
        double[] targets = WedgedUnderFloor.sideTargets(e.getX(), e.getZ(), halfWidth,
            c.minX(), c.minZ(), c.maxX(), c.maxZ(), velocity.x(), velocity.z());
        boolean anyUnloaded = false;
        boolean roomBeside = false;
        for (int i = 0; i < targets.length; i += 2) {
            double tx = targets[i];
            double tz = targets[i + 1];
            AABB moved = e.getBoundingBox().move(tx - e.getX(), 0.0, tz - e.getZ());
            // noCollision would sync-load an unloaded chunk on this side; try it on a later tick.
            // The same x/z span covers groundBelow's box under it.
            if (!WedgedUnderFloor.collisionChunksLoaded(moved.minX, moved.minZ, moved.maxX, moved.maxZ,
                    key -> level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) != null)) {
                anyUnloaded = true;
                continue;
            }
            if (!level.noCollision(e, moved) || CarriageSpace.overlapsAnyCarriage(level, moved)) continue;
            roomBeside = true;
            if (groundBelow(level, e, moved)) {
                int repeats = LOG_THROTTLES.computeIfAbsent(level, l -> new EjectLogThrottle())
                    .record(e.getId(), level.getGameTime());
                if (repeats >= 0) {
                    LOGGER.info("[DungeonTrain] Moved {} out of a carriage floor to ({}, {}, {}) source={} ageTicks={} tags={} repeats={}",
                        e.getType().toShortString(), tx, e.getY(), tz, sourceOf(e), e.tickCount, e.getTags(), repeats);
                }
                e.teleportTo(tx, e.getY(), tz);
                return;
            }
        }
        if (anyUnloaded) {
            // A side we couldn't check may be free once it loads: keep the mob rather than discard it.
            int repeats = WAIT_LOG_THROTTLES.computeIfAbsent(level, l -> new EjectLogThrottle())
                .record(e.getId(), level.getGameTime());
            if (repeats >= 0) {
                LOGGER.debug("[DungeonTrain] Left {} in a carriage floor at ({}, {}, {}) until the chunk beside it loads repeats={}",
                    e.getType().toShortString(), e.getX(), e.getY(), e.getZ(), repeats);
            }
            return;
        }
        String why = roomBeside ? "no ground beside it" : "no room beside it";
        if (ownerId != null) {
            // A pet is never discarded: it waits in the floor until its owner is back or a side clears.
            int repeats = LOG_THROTTLES.computeIfAbsent(level, l -> new EjectLogThrottle())
                .record(e.getId(), level.getGameTime());
            if (repeats >= 0) {
                LOGGER.info("[DungeonTrain] Left pet {} in a carriage floor at ({}, {}, {}): {}, owner offline repeats={}",
                    e.getType().toShortString(), e.getX(), e.getY(), e.getZ(), why, repeats);
            }
            return;
        }
        LOGGER.info("[DungeonTrain] Discarded {} stuck in a carriage floor at ({}, {}, {}): {} source={} ageTicks={} tags={}",
            e.getType().toShortString(), e.getX(), e.getY(), e.getZ(), why, sourceOf(e), e.tickCount, e.getTags());
        e.discard();
    }

    private static boolean nearPlayer(ServerLevel level, Entity e, AABBdc c, Vector3dc velocity, int roomLength) {
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            if (WedgedUnderFloor.withinRooms(p.getX(), p.getY(), p.getZ(), e.getX(), e.getY(), e.getZ(),
                    c.minX(), c.minZ(), c.maxX(), c.maxZ(), velocity.x(), velocity.z(), roomLength)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Leaves a mob that fell onto the tracks where it is and hits it for
     * {@link WedgedUnderFloor#CRUSH_DAMAGE} each {@link WedgedUnderFloor#CRUSH_INTERVAL_TICKS} it
     * spends under carriages. Time spent clear of the train is not counted.
     */
    private static void crush(ServerLevel level, LivingEntity e) {
        long now = level.getGameTime();
        long[] prev = CRUSH_TICKS.get(e);
        if (prev != null && prev[0] == now) return; // under two overlapping carriage boxes this tick
        if (prev == null) {
            int repeats = LOG_THROTTLES.computeIfAbsent(level, l -> new EjectLogThrottle()).record(e.getId(), now);
            if (repeats >= 0) {
                LOGGER.info("[DungeonTrain] Left {} on the tracks under a carriage near a player at ({}, {}, {}) source={} ageTicks={} tags={} repeats={}",
                    e.getType().toShortString(), e.getX(), e.getY(), e.getZ(), sourceOf(e), e.tickCount, e.getTags(), repeats);
            }
        }
        long ticksUnder = prev == null ? 1 : prev[1] + 1;
        CRUSH_TICKS.put(e, new long[] {now, ticksUnder});
        if (WedgedUnderFloor.crushDue(ticksUnder)) {
            e.hurt(level.damageSources().inWall(), WedgedUnderFloor.CRUSH_DAMAGE);
        }
    }

    /**
     * Something solid within {@link WedgedUnderFloor#MAX_SAFE_DROP_BLOCKS} under the moved box, so the
     * mob lands without a fall that hurts. Beside a bridge there is nothing, and the mob would drop.
     */
    private static boolean groundBelow(ServerLevel level, Entity e, AABB moved) {
        AABB under = new AABB(moved.minX, moved.minY - WedgedUnderFloor.MAX_SAFE_DROP_BLOCKS, moved.minZ,
            moved.maxX, moved.minY, moved.maxZ);
        return !level.noCollision(e, under);
    }

    /**
     * Where a wedged mob came from, for the log: placed by the train as carriage contents, spawned
     * by the Nether band's own spawner, or anything else (vanilla spawning, eggs, commands). Read
     * from the tags each source stamps, so a /bug log answers "who put it there" on its own.
     */
    private static String sourceOf(Entity e) {
        for (String tag : e.getTags()) {
            if (tag.startsWith(CarriageContentsPlacer.DT_CONTENTS_TAG_PREFIX)) return "carriage-contents";
            if (tag.equals(NetherMobSpawner.NETHER_MOB_TAG)) return "nether-band-spawner";
        }
        return "other";
    }
}
