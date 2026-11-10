package games.brennan.dungeontrain.train.engine;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.ship.ManagedShip;
import games.brennan.dungeontrain.ship.sable.CarriagePlotResolver;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Setting an Engine Block down in an ordinary carriage makes it a drifting one.
 *
 * <p>Registering a drifting carriage needs what only the spawn knew — the slot's stage and shell
 * variant — so the spawn path {@link #remember remembers} them for every carriage that could drift
 * (an enclosed slot with a stage, outside a portal). Placing an engine in one of those registers it
 * fresh, exactly as a shared slot is at spawn; it uploads the first time anyone changes it.</p>
 *
 * <p>Bounded like {@code PlacedCarriageFacts}: a long run passes unboundedly many carriages, and
 * anything far behind has been culled anyway.</p>
 */
public final class EngineConversion {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_ENTRIES = 4096;

    /** What the spawn knew about a carriage that could drift. */
    record Candidate(String stageId, String variantId) {}

    private static final Map<Integer, Candidate> BY_PIDX = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Candidate> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private EngineConversion() {}

    /** Spawn path: the carriage at {@code pIdx} could drift if an engine were set down in it. */
    public static synchronized void remember(int pIdx, String stageId, String variantId) {
        BY_PIDX.put(pIdx, new Candidate(stageId, variantId));
    }

    public static synchronized void clear() {
        BY_PIDX.clear();
    }

    private static synchronized Candidate candidate(int pIdx) {
        return BY_PIDX.get(pIdx);
    }

    /**
     * An engine was just set down at {@code pos} (shipyard space) outside any drifting build. Registers
     * the carriage holding it as a fresh drifting carriage. Returns the new instance, or null when the
     * position is not in a carriage that could drift.
     */
    public static SharedCarriageRegistry.Instance convert(ServerLevel level, BlockPos pos, int coal) {
        var sub = CarriagePlotResolver.subLevelAt(level, new ChunkPos(pos));
        if (sub == null) return null;
        UUID subLevelId = sub.getUniqueId();
        for (Trains.Carriage c : Trains.allCarriages(level)) {
            ManagedShip ship = c.ship();
            if (!ship.subLevelId().equals(subLevelId)) continue;
            TrainTransformProvider provider = c.provider();
            CarriageDims dims = provider.dims();
            int groupSize = provider.getGroupSize();
            int start = groupSize > 1 ? CarriagePlacer.halfPadLen(dims) : 0;
            BlockPos groupOrigin = provider.getShipyardOrigin();
            int dx = pos.getX() - groupOrigin.getX() - start;
            if (dx < 0) return null;
            int slot = dx / dims.length();
            if (slot >= groupSize) return null;
            BlockPos origin = groupOrigin.offset(start + slot * dims.length(), 0, 0);
            if (!inBox(pos, origin, dims)) return null;
            int pIdx = provider.getPIdx() + slot;
            Candidate cand = candidate(pIdx);
            if (cand == null) return null;
            SharedCarriageRegistry.Instance inst = SharedCarriageRegistry.register(level, subLevelId,
                    provider.getTrainId(), pIdx, origin, dims, cand.variantId(), false, false, "", null, null, 0,
                    cand.stageId(), SharedCarriageClient.Credits.EMPTY, SharedCarriageClient.Deaths.EMPTY);
            inst.setFuel(coal);
            // Queue the whole box: the carriage has never been uploaded, and the first flush must carry all of it.
            inst.enqueue(pos);
            inst.markBlockEdited();
            LOGGER.info("[DungeonTrain] Engine Block set down in carriage pIdx={} — it drifts now (stage={}).",
                    pIdx, cand.stageId());
            return inst;
        }
        return null;
    }

    private static boolean inBox(BlockPos pos, BlockPos origin, CarriageDims dims) {
        int x = pos.getX() - origin.getX(), y = pos.getY() - origin.getY(), z = pos.getZ() - origin.getZ();
        return x >= 0 && x < dims.length() && y >= 0 && y < dims.height() && z >= 0 && z < dims.width();
    }
}
