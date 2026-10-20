package games.brennan.dungeontrain.worldgen;

import dev.ryanhcode.sable.Sable;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.wind.TrainExposure;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * How much of a sphere's pull reaches an entity on the train. Shared by the server (mobs, items,
 * projectiles) and the client (its own player) so both make the same call from the same carriage blocks.
 *
 * <p>The carriage scans are the train wind's ({@link TrainExposure#classifyCover}: deck under the feet, side
 * walls, roof overhead, all read in Sable's plot frame), minus the wind's tunnel and lee tests. Three
 * outcomes:</p>
 * <ul>
 *   <li>{@link Exposure#FREE} — not over a carriage: the full pull.</li>
 *   <li>{@link Exposure#RIDER} — out on the train (a roof, a flatbed, a coupling pad): the rider factor.</li>
 *   <li>{@link Exposure#SHELTERED} — inside a carriage, under its roof or between its walls: no pull.</li>
 * </ul>
 *
 * <p>The scan reads up to ~80 carriage blocks, so {@link #classify} keeps each entity's verdict for a few ticks
 * ({@link SphereShelterCache}), keyed by entity id and anchored to the carriage Sable is tracking it on: step
 * off (or onto) a carriage and the verdict is dropped at once; move about the same carriage and it refreshes
 * within {@link SphereShelterCache#TTL_TICKS}. The server and the client each have their own memo (one thread
 * each). {@link #classifyUncached} is the raw scan.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SphereGravityShelter {

    private static final SphereShelterCache SERVER = new SphereShelterCache();
    private static final SphereShelterCache CLIENT = new SphereShelterCache();

    private SphereGravityShelter() {}

    public enum Exposure {
        FREE, RIDER, SHELTERED
    }

    /** {@link #classify} as a pull multiplier. */
    public static double factor(Entity e, double riderFactor) {
        return switch (classify(e)) {
            case FREE -> 1.0;
            case RIDER -> riderFactor;
            case SHELTERED -> 0.0;
        };
    }

    /** The entity's exposure, from the memo when it is fresh (see the class note), else a new scan. */
    public static Exposure classify(Entity e) {
        SphereShelterCache cache = cacheFor(e);
        if (!e.isAlive()) {
            cache.invalidate(e.getId());
            return classifyUncached(e);
        }
        Object anchor = Sable.HELPER.getTrackingSubLevel(e);
        return cache.get(e.getId(), anchor, e.level().getGameTime(), () -> classifyUncached(e));
    }

    /** The raw carriage scan, never memoised. */
    public static Exposure classifyUncached(Entity e) {
        TrainExposure.Result r = TrainExposure.classifyCover(TrainExposure.subLevelUnder(e.level(), e), e);
        return switch (r) {
            case NOT_ON_TRAIN -> Exposure.FREE;
            case UNDER_COVER, IN_TUNNEL, IN_LEE -> Exposure.SHELTERED;
            case EXPOSED -> Exposure.RIDER;
        };
    }

    /** Forget the client's memo (logout). */
    public static void clearClient() {
        CLIENT.clear();
    }

    /** Server memo size, for the debug echo. */
    public static int serverCacheSize() {
        return SERVER.size();
    }

    private static SphereShelterCache cacheFor(Entity e) {
        return e.level().isClientSide() ? CLIENT : SERVER;
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        SERVER.invalidate(event.getEntity().getId());
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) return;
        SERVER.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SERVER.clear();
    }
}
