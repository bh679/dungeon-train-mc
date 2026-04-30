package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Aggregation helpers that turn the flat list of all loaded carriage
 * sub-levels in a level back into "trains."
 *
 * <p>Each carriage is a separate Sable sub-level with its own
 * {@link TrainTransformProvider}. Sub-levels of the same train share a
 * {@link TrainTransformProvider#getTrainId() trainId} UUID; a "train" is
 * the collection of sub-levels that share a trainId. This class centralises
 * the grouping/lead/tail/min-max-pIdx logic so per-tick consumers don't
 * each reimplement it.</p>
 *
 * <h2>Lead vs tail</h2>
 * <ul>
 *   <li><b>Lead</b> = the carriage with the highest pIdx — furthest along
 *       the velocity direction. Per-train work that targets the runway
 *       (kill-ahead, future "what's in front" probes) runs on the lead.</li>
 *   <li><b>Tail</b> = the carriage with the lowest pIdx — furthest behind.
 *       Per-train work that paints chunks under the train (track gen,
 *       tunnel gen) currently runs on the tail because chunk-state queues
 *       live on each provider; choosing a stable end (the tail rarely
 *       changes once the train is moving forward) avoids re-discovering
 *       chunks every time a new carriage appends to the front.</li>
 * </ul>
 */
public final class Trains {

    /**
     * Lightweight pair of {@link ManagedShip} and its
     * {@link TrainTransformProvider}, returned together to avoid the cost
     * of re-fetching the provider via {@code getKinematicDriver()} every
     * time a consumer wants both.
     */
    public record Carriage(ManagedShip ship, TrainTransformProvider provider) {}

    private Trains() {}

    /**
     * Group every loaded carriage sub-level in {@code level} by trainId.
     * Order within each group is unspecified; use {@link #lead}, {@link #tail},
     * or sort explicitly when ordering matters.
     */
    public static Map<UUID, List<Carriage>> byTrainId(ServerLevel level) {
        Map<UUID, List<Carriage>> trains = new LinkedHashMap<>();
        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            if (!(ship.getKinematicDriver() instanceof TrainTransformProvider provider)) continue;
            trains.computeIfAbsent(provider.getTrainId(), k -> new ArrayList<>())
                .add(new Carriage(ship, provider));
        }
        return trains;
    }

    /**
     * Carriage with the highest pIdx in the train — the front of the train
     * relative to its velocity vector. Returns {@code null} on an empty list
     * (defensive; callers shouldn't normally hit this).
     */
    public static Carriage lead(List<Carriage> train) {
        if (train.isEmpty()) return null;
        Carriage best = train.get(0);
        for (int i = 1; i < train.size(); i++) {
            Carriage c = train.get(i);
            if (c.provider().getPIdx() > best.provider().getPIdx()) best = c;
        }
        return best;
    }

    /**
     * Carriage with the lowest pIdx in the train — the rear of the train.
     */
    public static Carriage tail(List<Carriage> train) {
        if (train.isEmpty()) return null;
        Carriage best = train.get(0);
        for (int i = 1; i < train.size(); i++) {
            Carriage c = train.get(i);
            if (c.provider().getPIdx() < best.provider().getPIdx()) best = c;
        }
        return best;
    }

    /** Highest pIdx in the train, or {@link Integer#MIN_VALUE} on empty. */
    public static int maxPIdx(List<Carriage> train) {
        int best = Integer.MIN_VALUE;
        for (Carriage c : train) {
            int p = c.provider().getPIdx();
            if (p > best) best = p;
        }
        return best;
    }

    /** Lowest pIdx in the train, or {@link Integer#MAX_VALUE} on empty. */
    public static int minPIdx(List<Carriage> train) {
        int best = Integer.MAX_VALUE;
        for (Carriage c : train) {
            int p = c.provider().getPIdx();
            if (p < best) best = p;
        }
        return best;
    }

    /**
     * Flat list of every loaded {@link TrainTransformProvider} carriage in
     * {@code level}, ungrouped. Drop-in replacement for the legacy
     * {@code TrainAssembler.getActiveTrainProviders} when callers don't care
     * about train boundaries.
     */
    public static List<Carriage> allCarriages(ServerLevel level) {
        List<Carriage> out = new ArrayList<>();
        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            if (!(ship.getKinematicDriver() instanceof TrainTransformProvider provider)) continue;
            out.add(new Carriage(ship, provider));
        }
        return out;
    }

    /**
     * Resolve a single train by id. Returns {@link Collections#emptyList} if
     * no carriages with that trainId are loaded.
     */
    public static List<Carriage> findById(ServerLevel level, UUID trainId) {
        List<Carriage> out = new ArrayList<>();
        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            if (!(ship.getKinematicDriver() instanceof TrainTransformProvider provider)) continue;
            if (!trainId.equals(provider.getTrainId())) continue;
            out.add(new Carriage(ship, provider));
        }
        return out;
    }
}
