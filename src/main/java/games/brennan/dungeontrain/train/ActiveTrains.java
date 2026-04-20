package games.brennan.dungeontrain.train;

import net.minecraft.core.BlockPos;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side in-memory registry of active Dungeon Train ships and the
 * per-carriage specs that were randomised at spawn time. Keyed by VS ship id.
 *
 * <p>Entries are added by {@link TrainAssembler#spawnTrain}, removed by
 * {@code deleteExistingTrains} or by the tick event when a ship unloads.
 *
 * <p>Intentionally non-persistent: specs are lost across server restart.
 * Frame blocks persist via VS's own save; un-populated carriages stay
 * un-populated after a restart. A future session can add NBT persistence
 * if the game requires it.
 */
public final class ActiveTrains {

    public record TrainData(
        List<CarriageSpec> specs,
        boolean[] populated,
        BlockPos originShipLocal
    ) {
        public int carriageCount() {
            return specs.size();
        }
    }

    private static final Map<Long, TrainData> TRAINS = new ConcurrentHashMap<>();

    private ActiveTrains() {}

    public static void register(long shipId, List<CarriageSpec> specs, BlockPos originShipLocal) {
        TRAINS.put(shipId, new TrainData(
            List.copyOf(specs),
            new boolean[specs.size()],
            originShipLocal.immutable()
        ));
    }

    public static void remove(long shipId) {
        TRAINS.remove(shipId);
    }

    public static TrainData get(long shipId) {
        return TRAINS.get(shipId);
    }

    public static Map<Long, TrainData> snapshot() {
        return Collections.unmodifiableMap(Map.copyOf(TRAINS));
    }

    public static void clear() {
        TRAINS.clear();
    }
}
