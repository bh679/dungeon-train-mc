package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.bodies.properties.BodyTransform;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.core.api.ships.properties.ShipInertiaData;
import org.valkyrienskies.core.api.ships.properties.ShipTransform;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-tick rolling-window driver: keeps each {@link TrainTransformProvider}-marked
 * ship's carriage blocks aligned with the union of per-player windows.
 *
 * For each player within {@link #NEAR_RADIUS} blocks of a train:
 *   - Project world position to ship-local via {@code ship.transform.worldToShip}.
 *   - Compute carriage index {@code pIdx = floor((localX - shipyardOriginX) / LENGTH)}.
 *   - Contribute carriage indices {@code [pIdx - halfBack, pIdx + halfFront]}.
 *
 * Active set = union across players. Diff against the provider's recorded indices:
 *   - Missing → place a carriage at that shipyard offset.
 *   - Stale → erase it, unless a player is currently inside (safety).
 */
public final class TrainWindowManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double NEAR_RADIUS = 128.0;
    private static final double NEAR_RADIUS_SQ = NEAR_RADIUS * NEAR_RADIUS;
    // Ticks to wait before accepting a reversed pIdx shift. ~1/3 second at
    // 20 Hz server tick: long enough to absorb single-tick flaps caused by
    // block-change/transform sync races, short enough that real direction
    // changes feel responsive.
    private static final int REVERSAL_COOLDOWN_TICKS = 6;

    private TrainWindowManager() {}

    public static void onLevelTick(ServerLevel level) {
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        for (LoadedServerShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            if (!(ship.getTransformProvider() instanceof TrainTransformProvider provider)) continue;
            updateWindow(level, ship, provider, players);
        }
    }

    private static void updateWindow(
        ServerLevel level,
        LoadedServerShip ship,
        TrainTransformProvider provider,
        List<ServerPlayer> players
    ) {
        int count = provider.getCount();
        int halfBack = (count - 1) / 2;
        int halfFront = count - halfBack - 1;
        BlockPos shipyardOrigin = provider.getShipyardOrigin();
        int originX = shipyardOrigin.getX();
        int originY = shipyardOrigin.getY();
        int originZ = shipyardOrigin.getZ();

        // Ship "near" check uses the provider's canonical position when
        // available — it's the stable world-space anchor unaffected by VS's
        // PIM drift. Fall back to the live transform only before the first
        // physics tick has captured the baseline.
        Vector3dc canonical = provider.getCanonicalPos();
        Vector3dc shipWorldPos = canonical != null ? canonical : ship.getTransform().getPosition();
        double shipWx = shipWorldPos.x();
        double shipWy = shipWorldPos.y();
        double shipWz = shipWorldPos.z();

        Set<Integer> current = provider.getActiveIndices();
        Set<Integer> desired = new HashSet<>();
        Set<Integer> occupied = new HashSet<>();

        for (ServerPlayer player : players) {
            double dx = player.getX() - shipWx;
            double dy = player.getY() - shipWy;
            double dz = player.getZ() - shipWz;
            if (dx * dx + dy * dy + dz * dz > NEAR_RADIUS_SQ) continue;

            // Use the provider's locked-frame worldToShip rather than
            // ship.getTransform().getWorldToShip(). The live transform's PIM
            // drifts as VS recomputes mass centroid each physics tick; that
            // drift fed into a ship-local pIdx oscillation that triggered the
            // rolling window to add+erase the same boundary carriage every
            // few ticks — a feedback loop that stopped the train from moving
            // and eventually crashed VS's entity-collision mixin with a CME.
            Vector3d local = provider.worldToLockedShip(player.getX(), player.getY(), player.getZ());
            if (local == null) {
                // Provider not initialized — fall back to live transform for this one tick.
                local = new Vector3d(player.getX(), player.getY(), player.getZ());
                ship.getTransform().getWorldToShip().transformPosition(local);
            }
            int pIdx = (int) Math.floor((local.x - originX) / (double) CarriageTemplate.LENGTH);

            occupied.add(pIdx);

            // Reversal debounce: if the player's pIdx flipped direction in the
            // last few ticks, the previous intended shift is still propagating
            // to clients. Honour the reversal only after REVERSAL_COOLDOWN
            // ticks have passed since the last committed shift. Within the
            // cooldown, treat pIdx as unchanged so the window doesn't flap.
            int committed = provider.getCommittedPIdx();
            int lastDir = provider.getLastShiftDirection();
            long now = level.getGameTime();
            long ticksSince = now - provider.getLastShiftTick();
            int effectivePIdx = pIdx;
            if (pIdx != committed) {
                int dir = Integer.signum(pIdx - committed);
                if (lastDir != 0 && dir == -lastDir && ticksSince < REVERSAL_COOLDOWN_TICKS) {
                    // Reversal too soon — suppress. Player still rides on committed.
                    effectivePIdx = committed;
                } else {
                    provider.setCommittedPIdx(pIdx);
                    provider.setLastShiftDirection(dir);
                    provider.setLastShiftTick(now);
                }
            }

            for (int i = effectivePIdx - halfBack; i <= effectivePIdx + halfFront; i++) {
                desired.add(i);
            }
        }

        if (desired.isEmpty()) return;

        Set<Integer> toAdd = new HashSet<>();
        for (Integer i : desired) {
            if (!current.contains(i)) toAdd.add(i);
        }
        Set<Integer> toErase = new HashSet<>();
        for (Integer i : current) {
            if (desired.contains(i)) continue;
            if (occupied.contains(i)) continue;
            toErase.add(i);
        }

        boolean flatbedTransition = false;
        for (Integer i : toAdd) {
            if (CarriageTemplate.typeForIndex(i) == CarriageTemplate.CarriageType.FLATBED) {
                flatbedTransition = true;
                break;
            }
        }
        if (!flatbedTransition) {
            for (Integer i : toErase) {
                if (CarriageTemplate.typeForIndex(i) == CarriageTemplate.CarriageType.FLATBED) {
                    flatbedTransition = true;
                    break;
                }
            }
        }

        boolean willLog = !toAdd.isEmpty() || !toErase.isEmpty();
        ShipTransform beforeTransform = willLog ? ship.getTransform() : null;
        ShipInertiaData beforeInertia = willLog ? ship.getInertiaData() : null;

        boolean mutated = false;
        for (Integer i : toAdd) {
            BlockPos carriageOrigin = new BlockPos(originX + i * CarriageTemplate.LENGTH, originY, originZ);
            CarriageTemplate.placeAt(level, carriageOrigin, CarriageTemplate.typeForIndex(i));
            current.add(i);
            mutated = true;
            LOGGER.debug("[DungeonTrain] Added carriage idx={} type={} at shipyard {}",
                i, CarriageTemplate.typeForIndex(i), carriageOrigin);
        }
        for (Integer i : toErase) {
            BlockPos carriageOrigin = new BlockPos(originX + i * CarriageTemplate.LENGTH, originY, originZ);
            CarriageTemplate.eraseAt(level, carriageOrigin);
            current.remove(i);
            mutated = true;
            LOGGER.debug("[DungeonTrain] Erased carriage idx={} type={} at shipyard {}",
                i, CarriageTemplate.typeForIndex(i), carriageOrigin);
        }

        // Capture the transform that VS re-derived after the block mutations,
        // BEFORE we push our compensation. This is the "rawAfter" the client
        // would see if we didn't compensate.
        ShipTransform rawAfterMutation = willLog ? ship.getTransform() : null;
        ShipInertiaData inertiaAfterMutation = willLog ? ship.getInertiaData() : null;

        // After voxel mutations, re-push the compensated transform in the
        // same server tick. This tightens the client-visible window between
        // "new voxels applied" and "new transform applied" — otherwise the
        // client can render the new blocks against the stale transform for
        // one frame, producing a brief backwards teleport (most visible when
        // a flatbed carriage enters/leaves the window because the flatbed's
        // small mass swings the COM by more blocks per shift).
        BodyTransform compensated = null;
        if (mutated) {
            compensated = provider.computeCompensatedTransform(ship.getTransform());
            if (compensated != null) {
                ship.unsafeSetTransform(compensated);
            }
        }

        if (willLog) {
            logWindowShift(level, ship, provider, toAdd, toErase, flatbedTransition,
                beforeTransform, beforeInertia, rawAfterMutation, inertiaAfterMutation, compensated);
        }
    }

    /**
     * Diagnostic for residual flatbed-entry train jump (tracked for v0.10.x).
     *
     * Emits one log line per tick where the rolling window shifted, covering
     * three snapshots that bracket the mutation:
     *   - BEFORE:    ship state observed before any block change this tick.
     *   - RAW AFTER: ship state observed after block mutation but BEFORE we
     *                pushed the compensated transform. This reveals whether VS
     *                auto-rewrites the transform on block change.
     *   - COMPENSATED: the transform we pushed via {@code unsafeSetTransform}.
     *
     * Compares {@code transform.positionInModel} with
     * {@code inertia.centerOfMassInShip} — if the renderer uses inertia COM
     * rather than the transform's PIM (hypothesis C), the two diverge and the
     * visible jump tracks the PIM/COM gap.
     *
     * Tagged [jump] for flatbed transitions (matches the user-reported symptom)
     * vs [shift] for non-flatbed transitions, so a log filter by [jump] still
     * gives the flatbed-only view.
     */
    private static void logWindowShift(
        ServerLevel level,
        LoadedServerShip ship,
        TrainTransformProvider provider,
        Set<Integer> toAdd,
        Set<Integer> toErase,
        boolean flatbedTransition,
        ShipTransform beforeTransform,
        ShipInertiaData beforeInertia,
        ShipTransform rawAfter,
        ShipInertiaData inertiaAfter,
        BodyTransform compensatedAfter
    ) {
        Vector3dc beforePIM = beforeTransform != null ? beforeTransform.getPositionInModel() : null;
        Vector3dc beforePos = beforeTransform != null ? beforeTransform.getPosition() : null;
        Vector3dc afterPIM = rawAfter != null ? rawAfter.getPositionInModel() : null;
        Vector3dc afterPos = rawAfter != null ? rawAfter.getPosition() : null;
        Vector3dc beforeCOM = beforeInertia != null ? beforeInertia.getCenterOfMassInShip() : null;
        Vector3dc afterCOM = inertiaAfter != null ? inertiaAfter.getCenterOfMassInShip() : null;
        Vector3dc afterCOMSpace = inertiaAfter != null ? inertiaAfter.getCenterOfMassInShipSpace() : null;

        Vector3d deltaPIM = (beforePIM != null && afterPIM != null)
            ? new Vector3d(afterPIM).sub(beforePIM)
            : new Vector3d();
        Vector3d deltaPos = (beforePos != null && afterPos != null)
            ? new Vector3d(afterPos).sub(beforePos)
            : new Vector3d();
        Vector3d deltaCOM = (beforeCOM != null && afterCOM != null)
            ? new Vector3d(afterCOM).sub(beforeCOM)
            : new Vector3d();
        Vector3d pimVsCom = (afterPIM != null && afterCOM != null)
            ? new Vector3d(afterPIM).sub(afterCOM)
            : new Vector3d();

        Vector3dc compPos = compensatedAfter != null ? compensatedAfter.getPosition() : null;
        Vector3dc compPIM = compensatedAfter != null ? compensatedAfter.getPositionInModel() : null;

        String tag = flatbedTransition ? "jump" : "shift";

        LOGGER.info(
            "[DungeonTrain:{}] tick={} committedPIdx={} add={} erase={} mass={} " +
            "lockedPIM={} canonicalPos={} " +
            "beforePIM={} afterPIM={} deltaPIM={} " +
            "beforePos={} afterPos={} deltaPos={} " +
            "beforeCOM={} afterCOM={} deltaCOM={} afterCOMSpace={} " +
            "pimVsComAfter={} " +
            "compPos={} compPIM={}",
            tag,
            level.getGameTime(),
            provider.getCommittedPIdx(),
            toAdd,
            toErase,
            inertiaAfter != null ? inertiaAfter.getShipMass() : Double.NaN,
            vecStr(provider.getLockedPositionInModel()),
            vecStr(provider.getCanonicalPos()),
            vecStr(beforePIM), vecStr(afterPIM), vecStr(deltaPIM),
            vecStr(beforePos), vecStr(afterPos), vecStr(deltaPos),
            vecStr(beforeCOM), vecStr(afterCOM), vecStr(deltaCOM), vecStr(afterCOMSpace),
            vecStr(pimVsCom),
            vecStr(compPos), vecStr(compPIM)
        );
    }

    private static String vecStr(Vector3dc v) {
        if (v == null) return "null";
        return String.format("(%.4f, %.4f, %.4f)", v.x(), v.y(), v.z());
    }
}
