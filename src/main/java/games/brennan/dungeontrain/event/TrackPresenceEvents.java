package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.primitives.AABBdc;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Awards the three "where am I relative to the train" advancements:
 * <ul>
 *   <li>{@code landed_on_tracks} — standing on the rail bed in the corridor
 *       (off the carriages, on the tracks the train rides).</li>
 *   <li>{@code left_train} — off every carriage <em>and</em> off the corridor
 *       (stepped off the side, out into the world). The request's "leaving the
 *       train <em>and</em> tracks".</li>
 *   <li>{@code returned_to_train} — back on a carriage after having been off it
 *       (whether they dropped to the tracks or jumped off entirely).</li>
 * </ul>
 *
 * <h2>Detection</h2>
 * The corridor is static world geometry — the train only moves +X, so
 * {@link TrackGeometry}'s {@code bedY} / Z-range never change. We read it from
 * any live carriage's {@link games.brennan.dungeontrain.train.TrainTransformProvider}.
 * "On a carriage" tests the carriage {@code worldAABB()} with <em>strict</em>
 * horizontal bounds (no outward pad) so a player standing or towering up
 * <em>beside</em> the train never reads as aboard, plus {@code +1} Y slack so
 * standing on the roof still counts. Only a genuine drop to the bed (below the
 * carriage floor) or off the side reads as "off"; the brief seam flicker
 * between adjacent carriage groups is absorbed by the off-grace before a
 * departure counts, so the return marker doesn't need the outward padding
 * {@link BoardingProgressEvents} uses for its (false-positive-tolerant) counter.
 *
 * <p>All three are one-shot markers — vanilla advancement dedupe means firing
 * the same id every qualifying scan is harmless, so the only persistent state
 * needed is the per-player latch/grace driving the leave/return transitions.
 * That state is transient (rebuilt each session); since players always spawn
 * aboard ({@link PlayerJoinEvents}) it re-establishes before they can wander.</p>
 *
 * <p>Throttled to once every {@link #SCAN_PERIOD_TICKS} ticks per level,
 * matching {@link BoardingProgressEvents} / {@link RoofRunEvents}.</p>
 */
public final class TrackPresenceEvents {

    /** Per-level scan period, in ticks. Matches the other train scanners. */
    private static final int SCAN_PERIOD_TICKS = 10;

    /**
     * Vertical slack (blocks) above a carriage's {@code worldAABB} top that
     * still counts as aboard — covers standing on the roof and sprint-jumping
     * from it (~1.25 blocks peak). Matched to {@link RoofRunEvents}'s
     * {@code JUMP_HEADROOM} so a roof jump never reads as a departure.
     * Horizontal bounds are intentionally strict (no outward pad) so a player
     * beside or towering up next to the train never reads as aboard; the brief
     * seam flicker between groups that the pad would otherwise hide is instead
     * absorbed by {@link #OFF_GRACE_SCANS}.
     */
    private static final double ROOF_STAND_SLACK = 3.0;

    /**
     * How far above the rail bed (in blocks of feet-Y) still counts as "on the
     * tracks". The bed top sits at {@code bedY+1} (feet there when standing) and
     * the carriage floor at {@code bedY+2}, so this window stays below the
     * carriage floor and never collides with riding the train.
     */
    private static final double BED_Y_SLACK = 2.0;

    /**
     * Blocks beyond the bed's Z edges a player must be to count as "off the
     * corridor" for {@code left_train} — they've stepped off the side, not just
     * to the rail edge.
     */
    private static final double OFF_CORRIDOR_MARGIN = 1.0;

    /**
     * Consecutive off-carriage scans before a departure "counts" for the return
     * marker. 2 scans × 10 ticks ≈ 1 s — long enough that a coupling-seam
     * crossing (already bridged by padding) or a momentary hop never reads as
     * leaving-then-returning.
     */
    private static final int OFF_GRACE_SCANS = 2;

    /** Per-player transition state. Presence is rebuilt lazily each session. */
    private static final Map<UUID, State> STATES = new HashMap<>();

    private TrackPresenceEvents() {}

        public static void onLevelTick(net.minecraft.world.level.Level tickedLevel) {
        if (!(tickedLevel instanceof ServerLevel level)) return;
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0) return;

        List<Trains.Carriage> carriages = Trains.allCarriages(level);
        if (carriages.isEmpty()) return;

        TrackGeometry g = firstTrackGeometry(carriages);
        if (g == null) return;

        double bedMinZ = g.trackZMin();
        double bedMaxZ = g.trackZMax() + 1.0; // block width: bed spans [trackZMin, trackZMax+1)

        for (ServerPlayer player : level.players()) {
            double px = player.getX();
            double py = player.getY();
            double pz = player.getZ();

            boolean onCarriage = isOnAnyCarriage(carriages, px, py, pz);
            boolean onTracks = !onCarriage
                && pz >= bedMinZ && pz <= bedMaxZ
                && py >= g.bedY() && py <= g.bedY() + BED_Y_SLACK;
            boolean offCorridor = !onCarriage
                && (pz < bedMinZ - OFF_CORRIDOR_MARGIN || pz > bedMaxZ + OFF_CORRIDOR_MARGIN);

            State s = STATES.computeIfAbsent(player.getUUID(), k -> new State());

            if (onTracks) {
                ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "landed_on_tracks");
            }
            if (onCarriage || onTracks) {
                s.wasOnTrainOrTracks = true;
            }

            if (onCarriage) {
                s.hasBeenAboard = true;
                s.offCarriageScans = 0;
                if (s.leftCarriageSinceAboard) {
                    ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "returned_to_train");
                    s.leftCarriageSinceAboard = false;
                }
            } else {
                if (s.hasBeenAboard) {
                    s.offCarriageScans++;
                    if (s.offCarriageScans >= OFF_GRACE_SCANS) {
                        s.leftCarriageSinceAboard = true;
                    }
                }
                if (s.wasOnTrainOrTracks && offCorridor) {
                    ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "left_train");
                }
            }
        }

        // Drop state for fully-disconnected players so the map doesn't leak.
        STATES.keySet().removeIf(uuid -> level.getServer().getPlayerList().getPlayer(uuid) == null);
    }

    /** First non-null track geometry across the train's carriages (all share one). */
    @Nullable
    private static TrackGeometry firstTrackGeometry(List<Trains.Carriage> carriages) {
        for (Trains.Carriage c : carriages) {
            TrackGeometry g = c.provider().getTrackGeometry();
            if (g != null) return g;
        }
        return null;
    }

    /**
     * True if the player is within a carriage's {@code worldAABB}: strict
     * horizontal bounds (no outward pad — so standing or towering up beside the
     * train does NOT count as aboard) with {@code ROOF_STAND_SLACK} Y headroom
     * so standing on the roof still counts. A momentary seam flicker between
     * adjacent groups is fine — {@link #OFF_GRACE_SCANS} keeps it from counting
     * as a departure.
     */
    private static boolean isOnAnyCarriage(List<Trains.Carriage> carriages, double px, double py, double pz) {
        for (Trains.Carriage c : carriages) {
            AABBdc bb = c.ship().worldAABB();
            if (px < bb.minX() || px > bb.maxX()) continue;
            if (py < bb.minY() || py > bb.maxY() + ROOF_STAND_SLACK) continue;
            if (pz < bb.minZ() || pz > bb.maxZ()) continue;
            return true;
        }
        return false;
    }

    /** Mutable per-player transition state for the leave/return state machine. */
    private static final class State {
        /** Has been on a carriage or the tracks at least once — latches left_train. */
        boolean wasOnTrainOrTracks;
        /** Has been on a carriage at least once — gates the return marker. */
        boolean hasBeenAboard;
        /** Consecutive off-carriage scans since last aboard. */
        int offCarriageScans;
        /** Departed a carriage (past the grace) and not yet re-boarded. */
        boolean leftCarriageSinceAboard;
    }
}
