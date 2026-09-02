package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.Shipyards;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Lightweight pair of {@link ManagedShip} and its
     * {@link TrainTransformProvider}, returned together to avoid the cost
     * of re-fetching the provider via {@code getKinematicDriver()} every
     * time a consumer wants both.
     */
    public record Carriage(ManagedShip ship, TrainTransformProvider provider) {}

    /**
     * Authoritative registry of every carriage group ever spawned via
     * {@link TrainAssembler#spawnGroup}, keyed by trainId then anchor pIdx.
     * Sourced ONLY by {@link TrainAssembler#spawnGroup} on success and
     * cleared by {@link TrainAssembler#deleteAllTrains} (and by
     * {@code WorldLifecycleEvents.onServerStopped} for cross-session
     * safety). The appender's wait-for-Sable-settle check reads back the
     * stored {@link ManagedShip} references to detect when a freshly-
     * spawned sub-level has ticked at least once (its {@code worldAABB}
     * becomes non-zero) before allowing the next auto spawn.
     */
    private static final Map<UUID, Map<Integer, ManagedShip>> SPAWNED_GROUPS = new ConcurrentHashMap<>();

    /**
     * The world-X each anchor's worldgen gate was first resolved at, keyed by trainId then anchor
     * pIdx. Written once per anchor by {@link #gateWorldXOrRecord}; read back on every later spawn of
     * that same anchor.
     *
     * <p><b>Why this exists.</b> A group's stage (and therefore its gated variant pool) is resolved
     * only on a fresh spawn, from the group's ACTUAL placed world-X at that instant — deliberately,
     * so a carriage's dimension flips in lockstep with the static world-X band beneath it. But an
     * anchor can be spawned FRESH more than once: {@code TrainCarriageAppender.cleanupGhostAnchors}
     * deletes and {@link #unregisterGroup}s anchors past the visible edge, after which the appender
     * re-spawns that same pIdx. The train has travelled in the meantime, so the second spawn samples
     * a different band, resolves a different stage, and — because the variant pick indexes into the
     * stage-gated pool — builds a different carriage. That is the "exactly one carriage at render
     * distance loads from a different stage" report. Remembering the first gate X makes a re-spawn
     * reproduce what was there before.</p>
     *
     * <p>Deliberately NOT touched by {@link #unregisterGroup} — surviving that removal is the entire
     * point. Cleared with the rest of the registry by {@link #clearRegistry}.</p>
     */
    private static final Map<UUID, Map<Integer, Integer>> GATE_WORLD_X = new ConcurrentHashMap<>();

    /**
     * Cap on remembered anchors per train. A few thousand boxed ints is negligible, and the memory
     * only has to outlive the frontier churn that re-spawns an anchor. Past the cap the anchors
     * farthest from the one being spawned are dropped; such an anchor re-rolls if it is ever spawned
     * again, which is exactly today's behaviour at that distance.
     */
    static final int MAX_REMEMBERED_GATE_ANCHORS = 4096;

    private Trains() {}

    /**
     * The world-X to gate {@code anchorPIdx}'s worldgen stage from. The first call for a given
     * {@code (trainId, anchorPIdx)} records {@code placedWorldX} and returns it; every later call
     * returns that first value and ignores the one passed in.
     *
     * <p>Callers still PLACE the group at its live world position — only the value fed to
     * {@code GateContext.forCarriageAtWorldX} is pinned, so a re-spawned anchor rebuilds the same
     * stage and variant instead of re-rolling against wherever the train has since travelled. See
     * {@link #GATE_WORLD_X}.</p>
     */
    public static int gateWorldXOrRecord(UUID trainId, int anchorPIdx, int placedWorldX) {
        Map<Integer, Integer> map = GATE_WORLD_X.computeIfAbsent(trainId, k -> new ConcurrentHashMap<>());
        Integer remembered = map.putIfAbsent(anchorPIdx, placedWorldX);
        if (remembered != null) return remembered;
        evictFarthestFrom(map, anchorPIdx, MAX_REMEMBERED_GATE_ANCHORS);
        return placedWorldX;
    }

    /**
     * Trim {@code map} down to {@code cap} entries by dropping the anchors farthest from
     * {@code keepNear} — the anchor just spawned, and therefore the centre of the region still in
     * play. A no-op while the map is within the cap. Package-private and free of Minecraft types so
     * the eviction rule is unit-testable.
     */
    static void evictFarthestFrom(Map<Integer, Integer> map, int keepNear, int cap) {
        if (map.size() <= cap) return;
        List<Integer> byDistance = new ArrayList<>(map.keySet());
        byDistance.sort(Comparator.comparingLong(a -> -Math.abs((long) a - keepNear)));
        for (Integer anchor : byDistance) {
            if (map.size() <= cap) break;
            map.remove(anchor);
        }
    }

    /**
     * Record a freshly-spawned carriage group in the registry. Called
     * from {@link TrainAssembler#spawnGroup} after a successful
     * {@link Shipyards#assemble} + driver-attach pass.
     */
    public static void registerSpawned(UUID trainId, int anchorPIdx, ManagedShip ship) {
        SPAWNED_GROUPS
            .computeIfAbsent(trainId, k -> new ConcurrentHashMap<>())
            .put(anchorPIdx, ship);
    }

    /**
     * Snapshot of every anchor pIdx known to belong to {@code trainId}.
     * Returns an empty set for an unknown trainId. Defensive copy.
     */
    public static Set<Integer> knownAnchors(UUID trainId) {
        Map<Integer, ManagedShip> map = SPAWNED_GROUPS.get(trainId);
        if (map == null) return Set.of();
        return new HashSet<>(map.keySet());
    }

    /**
     * Snapshot of every registered group for {@code trainId} as
     * {@code (anchorPIdx, ship)} pairs. Returns an empty map for an
     * unknown trainId. Defensive copy.
     */
    public static Map<Integer, ManagedShip> knownGroups(UUID trainId) {
        Map<Integer, ManagedShip> map = SPAWNED_GROUPS.get(trainId);
        if (map == null) return Map.of();
        return new LinkedHashMap<>(map);
    }

    /**
     * Drop a single anchor's registry entry. Used by the appender to
     * forget "ghost" anchors — sub-levels that were registered on spawn
     * but subsequently culled by Sable before placement could complete
     * and never reloaded. Without this, the registry's grow-only nature
     * left those anchors permanently inflating
     * {@link #knownAnchors(UUID)}, so every future spawn was placed past
     * the ghosts and the train showed a visible gap. Caller must have
     * confirmed via {@code Shipyards.findAll()} that the anchor's
     * sub-level is no longer loaded before invoking this. Returns the
     * previously-registered {@link ManagedShip} (or {@code null} if the
     * anchor wasn't in the registry) so the caller can perform any
     * needed Sable-side cleanup.
     */
    public static ManagedShip unregisterGroup(UUID trainId, int anchorPIdx) {
        Map<Integer, ManagedShip> map = SPAWNED_GROUPS.get(trainId);
        if (map == null) return null;
        return map.remove(anchorPIdx);
    }

    /**
     * Every train id with at least one registered group, visible or not. The appender's
     * remote-player wake pass walks this to find trains that have been culled entirely — the
     * one state in which {@link #byTrainId} (built from loaded sub-levels) cannot name them.
     * Defensive copy.
     */
    public static Set<UUID> registeredTrainIds() {
        return new HashSet<>(SPAWNED_GROUPS.keySet());
    }

    /**
     * Replace the registered handle for an anchor after its sub-level was reloaded from Sable
     * holding. A reload allocates a NEW sub-level instance; the registry's old handle still
     * names the same stable id but no longer refers to the live object, so anything keyed by
     * instance (force-load tickets, {@code delete}) would act on the wrong one. No-op for an
     * unknown train or anchor: registration is the spawn path's job.
     */
    public static void refreshHandle(UUID trainId, int anchorPIdx, ManagedShip ship) {
        Map<Integer, ManagedShip> map = SPAWNED_GROUPS.get(trainId);
        if (map == null || !map.containsKey(anchorPIdx)) return;
        map.put(anchorPIdx, ship);
    }

    /** Clear every train registration. Wired to server stop and to {@code TrainAssembler.deleteAllTrains}. */
    public static void clearRegistry() {
        SPAWNED_GROUPS.clear();
        GATE_WORLD_X.clear();
    }

    /**
     * Group every loaded carriage sub-level in {@code level} by trainId.
     * Order within each group is unspecified; use {@link #lead}, {@link #tail},
     * or sort explicitly when ordering matters.
     */
    public static Map<UUID, List<Carriage>> byTrainId(ServerLevel level) {
        Map<UUID, List<Carriage>> trains = new LinkedHashMap<>();
        int totalShips = 0;
        int withTrainProvider = 0;
        for (ManagedShip ship : Shipyards.of(level).findAll()) {
            totalShips++;
            if (!(ship.getKinematicDriver() instanceof TrainTransformProvider provider)) continue;
            withTrainProvider++;
            trains.computeIfAbsent(provider.getTrainId(), k -> new ArrayList<>())
                .add(new Carriage(ship, provider));
        }
        if (LOGGER.isDebugEnabled()) {
            StringBuilder summary = new StringBuilder();
            for (Map.Entry<UUID, List<Carriage>> e : trains.entrySet()) {
                if (summary.length() > 0) summary.append("; ");
                summary.append("trainId=").append(e.getKey()).append(" carriages=[");
                boolean first = true;
                for (Carriage c : e.getValue()) {
                    if (!first) summary.append(", ");
                    first = false;
                    summary.append("pIdx=").append(c.provider().getPIdx())
                        .append(" ship=").append(c.ship().id())
                        .append(" sy=").append(c.provider().getShipyardOrigin().getX());
                }
                summary.append("]");
            }
            LOGGER.debug("[DungeonTrain] Trains.byTrainId: totalShips={} withTrainProvider={} trains={{{}}}",
                totalShips, withTrainProvider, summary);
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

    /**
     * Highest CARRIAGE pIdx in the train (the very front carriage of the
     * lead group), or {@link Integer#MIN_VALUE} on empty.
     *
     * <p>For groups of size > 1 this is the lead group's anchor pIdx +
     * (groupSize − 1) — the group's last carriage. For groupSize=1 it
     * matches the lead's anchor pIdx exactly.</p>
     */
    public static int maxPIdx(List<Carriage> train) {
        int best = Integer.MIN_VALUE;
        for (Carriage c : train) {
            int hi = c.provider().getGroupHighestPIdx();
            if (hi > best) best = hi;
        }
        return best;
    }

    /**
     * Lowest CARRIAGE pIdx in the train (the very rear carriage of the
     * tail group, which is the tail group's anchor), or
     * {@link Integer#MAX_VALUE} on empty.
     */
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
