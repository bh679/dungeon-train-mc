package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.event.NetherMobSpawner;
import games.brennan.dungeontrain.util.DtLogging;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3dc;
import org.joml.primitives.AABBdc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Per-tick safety net: moves any mob stuck in a carriage floor out to the ground beside the
 * carriage. See {@link WedgedUnderFloor} for how one gets there and the rule that finds it.
 *
 * <p>Spawning into a carriage is refused at the source ({@code NetherMobSpawner},
 * {@code TrainSpawnGuard}); this catches the rest — mobs already wedged in an existing save, or one
 * that gets in some other way. If neither side of the carriage has room, the mob is discarded, which
 * is what happened to every one of them before 0.1397.0.</p>
 */
public final class WedgedMobEjector {

    private static final Logger LOGGER = LoggerFactory.getLogger(DtLogging.JITTER);

    /** One per level, so a mob that keeps getting back in logs once per window, not per move. */
    private static final Map<ServerLevel, EjectLogThrottle> LOG_THROTTLES = new WeakHashMap<>();

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
            for (Entity e : wedged) {
                moveOut(level, e, c, velocity);
            }
        }
    }

    private static void moveOut(ServerLevel level, Entity e, AABBdc c, Vector3dc velocity) {
        double halfWidth = e.getBbWidth() / 2.0;
        double[] targets = WedgedUnderFloor.sideTargets(e.getX(), e.getZ(), halfWidth,
            c.minX(), c.minZ(), c.maxX(), c.maxZ(), velocity.x(), velocity.z());
        for (int i = 0; i < targets.length; i += 2) {
            double tx = targets[i];
            double tz = targets[i + 1];
            AABB moved = e.getBoundingBox().move(tx - e.getX(), 0.0, tz - e.getZ());
            if (level.noCollision(e, moved) && !CarriageSpace.overlapsAnyCarriage(level, moved)) {
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
        LOGGER.info("[DungeonTrain] Discarded {} stuck in a carriage floor at ({}, {}, {}): no room beside it source={} ageTicks={} tags={}",
            e.getType().toShortString(), e.getX(), e.getY(), e.getZ(), sourceOf(e), e.tickCount, e.getTags());
        e.discard();
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
