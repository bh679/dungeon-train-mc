package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.CometShowerPacket;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.worldgen.CometShowerPlan;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Comet showers over the trains, one at a time per train ({@link CometShowerPlan} says how big, how fast and how
 * often). A shower is how every strike on the train comes: watchers are told its direction at once, so their far
 * sky fills with comets falling that way ({@link CometShowerPacket}); then its strikes fly in along that same line,
 * one at a time, never launching while the last is still in the air; then the sky stays full a while after the
 * last. Server thread only.
 */
final class CometShowers {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * A shower on {@code trainId}, aimed near {@code player}. {@code nextArrival} is when its next strike should
     * land; {@code activeId} is the strike in the air (-1 for none); {@code endTick} is when the sky clears, -1
     * until the last strike is down.
     */
    private record Shower(UUID trainId, UUID player, Vec3 approach, CometShowerPlan plan, int strikesLeft,
                          long nextArrival, int activeId, long endTick) {

        Shower launched(int id, long arrival) {
            return new Shower(trainId, player, approach, plan, strikesLeft - 1, arrival + plan.gapTicks(), id,
                endTick);
        }

        Shower ending(long at) {
            return new Shower(trainId, player, approach, plan, strikesLeft, nextArrival, -1, at);
        }
    }

    private static Map<UUID, Shower> showers = Map.of();

    private CometShowers() {}

    /** Whether {@code trainId} has a shower on, from its first comets to the sky clearing. */
    static boolean isOn(UUID trainId) {
        return showers.containsKey(trainId);
    }

    /**
     * Start a shower over {@code watcher}'s part of {@code train} from {@code plan}, unless it already has one.
     * @return true if one started
     */
    static boolean start(ServerLevel level, UUID trainId, List<Trains.Carriage> train, ServerPlayer watcher,
                         CometShowerPlan plan) {
        if (isOn(trainId)) return false;
        Vec3 approach = SpheresComet.approach(train, level.random);
        if (approach == null) return false;
        int strikes = plan.rollStrikes(level.random.nextDouble());
        tellSky(level, watcher.position(), approach, plan, plan.durationTicks(strikes));
        long firstArrival = level.getGameTime() + plan.leadTicks();
        put(new Shower(trainId, watcher.getUUID(), approach, plan, strikes, firstArrival, -1, -1L));
        LOGGER.debug("[DungeonTrain] Comet shower on train {}: {} strikes, first at tick {}", trainId, strikes,
            firstArrival);
        return true;
    }

    /** Step every shower: fly in a strike when it is due and the sky is clear of the last one; end finished ones. */
    static void tick(ServerLevel level, Map<UUID, List<Trains.Carriage>> trains) {
        if (showers.isEmpty()) return;
        long now = level.getGameTime();
        Map<UUID, Shower> next = new HashMap<>();
        for (Shower s : showers.values()) {
            List<Trains.Carriage> train = trains.get(s.trainId());
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(s.player());
            if (train == null || player == null) continue;
            Shower stepped = step(level, s, train, player, now);
            if (stepped != null) next.put(stepped.trainId(), stepped);
        }
        showers = Map.copyOf(next);
    }

    /** {@code s} one tick on, or null once its sky has cleared. */
    private static Shower step(ServerLevel level, Shower s, List<Trains.Carriage> train, ServerPlayer player,
                               long now) {
        boolean clear = s.activeId() < 0 || !SpheresCometEvents.isLive(s.activeId());
        if (s.strikesLeft() > 0) {
            if (!clear) return s;
            double speed = s.plan().speedScale();
            long arrival = now + Math.round(SpheresComet.flightTicks(s.approach(), speed));
            if (arrival < s.nextArrival()) return s;
            SpheresComet comet = SpheresCometEvents.launchStrike(level, train, player.position(), s.approach(), speed);
            return comet == null ? s.launched(-1, arrival) : s.launched(comet.id(), arrival);
        }
        if (s.endTick() < 0L) {
            if (!clear) return s;
            // Strikes can run late waiting on one another, so the sky's time is renewed from the last landing.
            tellSky(level, player.position(), s.approach(), s.plan(), s.plan().tailTicks());
            return s.ending(now + s.plan().tailTicks());
        }
        return now >= s.endTick() ? null : s;
    }

    /** Tell watchers near {@code at} the sky is in a shower falling along {@code approach} for {@code ticks}. */
    private static void tellSky(ServerLevel level, Vec3 at, Vec3 approach, CometShowerPlan plan, int ticks) {
        Vec3 fall = approach.normalize().scale(-1.0);
        SpheresComet.tell(level, at, new CometShowerPacket((float) fall.x, (float) fall.y, (float) fall.z,
            (float) plan.speedScale(), ticks));
    }

    private static void put(Shower s) {
        Map<UUID, Shower> next = new HashMap<>(showers);
        next.put(s.trainId(), s);
        showers = Map.copyOf(next);
    }

    static void clear() {
        showers = Map.of();
    }
}
