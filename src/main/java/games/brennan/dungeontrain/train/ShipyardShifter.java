package games.brennan.dungeontrain.train;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.valkyrienskies.core.api.ships.LoadedServerShip;

import java.util.List;

/**
 * Keeps a train's reference frame (pivot + canonicalPos + pinned COM)
 * close enough to the active carriage voxels that VS 2.4.11's internal
 * 128-chunk distance limit between a ship and its outermost
 * rendered/collidable voxel is never crossed.
 *
 * <h2>Why</h2>
 * With our ship pinned at spawn (see {@link TrainTransformProvider}'s
 * {@code lockedPositionInModel} and {@link ShipInertiaLocker}'s COM pin),
 * the pivot stays at the spawn location forever. The player walking the
 * rolling-window train past ~228 carriages puts their carriage voxel >
 * 2048 blocks (= 128 chunks) from the pivot. VS refuses to trust the
 * player-is-on-a-ship state for voxels that far out, so the server
 * rejects forward movement.
 *
 * <h2>How</h2>
 * When the player's shipyard-X distance from the pivot exceeds
 * {@link #SHIFT_TRIGGER_BLOCKS}, advance pivot + canonicalPos + pinned
 * COM by {@code offset − SHIFT_LEAVE_LEAD_BLOCKS} (in the direction of
 * the offset, so backwards-riding trains work symmetrically). The
 * compensation math in {@link TrainTransformProvider#shiftReference}
 * guarantees that no voxel visibly moves — only the ship's internal
 * reference shifts, giving the player more runway before the VS limit.
 *
 * <h2>Cadence</h2>
 * One check per server tick; one shift per triggered ship per tick
 * (multiple shifts would stack visibly even with the compensation math
 * because {@code canonicalPos} updates feed VS's physics thread one tick
 * later). With the default thresholds, a sprint-speed player triggers
 * one shift every ~1000 blocks of forward travel — effectively invisible.
 */
public final class ShipyardShifter {

    private static final Logger JITTER_LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");

    /** Trigger a shift when the farthest player is this many blocks past pivot in shipyard X. */
    private static final double SHIFT_TRIGGER_BLOCKS = 900.0;
    /** Post-shift distance from pivot to the triggering player — leaves ~1100 blocks of headroom. */
    private static final double SHIFT_LEAVE_LEAD_BLOCKS = 450.0;
    /**
     * Minimum shift magnitude. Below this it's not worth the atomic
     * physics-thread/server-thread coordination cost for a single tick.
     * Also prevents repeated tiny shifts when the player hovers near the
     * threshold.
     */
    private static final double MIN_SHIFT_BLOCKS = 50.0;

    private ShipyardShifter() {}

    public static void shiftIfNeeded(
        ServerLevel level,
        List<LoadedServerShip> trains,
        List<ServerPlayer> players
    ) {
        if (players.isEmpty() || trains.isEmpty()) return;
        for (LoadedServerShip ship : trains) {
            if (!(ship.getTransformProvider() instanceof TrainTransformProvider provider)) continue;
            considerShift(level, ship, provider, players);
        }
    }

    private static void considerShift(
        ServerLevel level,
        LoadedServerShip ship,
        TrainTransformProvider provider,
        List<ServerPlayer> players
    ) {
        Vector3dc pivot = provider.getLockedPositionInModel();
        if (pivot == null) return; // first physics tick hasn't run yet

        // Find the player with the largest |shipyardX − pivot.x| — that's
        // the one closest to the VS limit. Only consider players plausibly
        // riding this ship (distance check on shipyard-space X suffices,
        // since our trains run on X and the carriage window is narrow in
        // Y/Z).
        double worstOffset = 0.0;
        ServerPlayer worstPlayer = null;
        for (ServerPlayer player : players) {
            Vector3d local = new Vector3d(player.getX(), player.getY(), player.getZ());
            ship.getTransform().getWorldToShip().transformPosition(local);
            double offset = local.x - pivot.x();
            if (Math.abs(offset) > Math.abs(worstOffset)) {
                worstOffset = offset;
                worstPlayer = player;
            }
        }
        if (worstPlayer == null || Math.abs(worstOffset) < SHIFT_TRIGGER_BLOCKS) return;

        // Shift in the direction of the worst offset so we move the pivot
        // toward the player. Magnitude = overshoot beyond the lead margin.
        double shiftMag = Math.signum(worstOffset) * (Math.abs(worstOffset) - SHIFT_LEAVE_LEAD_BLOCKS);
        if (Math.abs(shiftMag) < MIN_SHIFT_BLOCKS) return;

        Vector3d delta = new Vector3d(shiftMag, 0.0, 0.0);

        // Capture before/after for the [stuck.shift] log — cheap and the
        // log fires at most once per ~1000 blocks of forward travel.
        Vector3dc canonBefore = provider.getCanonicalPos();
        double canonBeforeX = canonBefore == null ? Double.NaN : canonBefore.x();
        double pivotBeforeX = pivot.x();

        provider.shiftReference(delta);
        ShipInertiaLocker.LockedInertia locked = provider.getLockedInertia();
        if (locked != null) {
            ShipInertiaLocker.LockedInertia shifted = ShipInertiaLocker.shiftCom(locked, delta);
            provider.setLockedInertia(shifted);
            // Immediately restore into VS's live inertia fields so the next
            // physics tick's getCenterOfMass() read sees the new COM.
            ShipInertiaLocker.restore(ship, shifted);
        }

        Vector3dc canonAfter = provider.getCanonicalPos();
        Vector3dc pivotAfter = provider.getLockedPositionInModel();
        JITTER_LOGGER.info(
            "[stuck.shift] tick={} shipId={} player={} offsetBefore={} shiftBy={} "
                + "pivotX={} -> {} canonicalX={} -> {}",
            level.getGameTime(), ship.getId(),
            worstPlayer.getUUID(),
            String.format("%.2f", worstOffset),
            String.format("%.2f", shiftMag),
            String.format("%.2f", pivotBeforeX),
            pivotAfter == null ? "null" : String.format("%.2f", pivotAfter.x()),
            String.format("%.2f", canonBeforeX),
            canonAfter == null ? "null" : String.format("%.2f", canonAfter.x()));
    }
}
