package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.Trains;
import games.brennan.dungeontrain.portal.PortalTwinSpace;
import net.minecraft.util.Mth;
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
 * <h2>Inside twin space, none of this counts</h2>
 * The hallway-portal system stamps its twin corridors and rooms into the sealed world
 * outside the terrain — the basement under the bedrock ordinarily, the attic over the
 * inverted lid inside the upside-down band (see
 * {@link games.brennan.dungeontrain.portal.PortalTwinSpace}). A player walking through
 * a portal is therefore nowhere near a carriage, and on position alone reads as having
 * jumped off — which would grant {@code left_train} and, on the way back in,
 * {@code returned_to_train}, for walking through a door.
 *
 * <p>So inside that space — where nothing legitimate happens, since no terrain reaches
 * there and no player can dig into it — {@link #step} emits nothing and returns the
 * state <em>unchanged</em>.</p>
 *
 * <p>Frozen rather than reset, deliberately: the player entered from a carriage,
 * so the state already says aboard with no departure latched, and picking it back
 * up on the far side is what makes the whole trip a no-op. Resetting would clear
 * {@code hasBeenAboard} and cost them a later, genuine {@code returned_to_train}.</p>
 *
 * <p>Throttled to once every {@link #SCAN_PERIOD_TICKS} ticks per level,
 * matching {@link BoardingProgressEvents} / {@link RoofRunEvents}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
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

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
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

            // "In a portal room", which is only the same as "below the bedrock" outside the
            // upside-down band — in it, rooms stand in the attic over the inverted lid instead.
            boolean belowBedrock = PortalTwinSpace.isInside(level, Mth.floor(px), py);

            State before = STATES.getOrDefault(player.getUUID(), State.INITIAL);
            PresenceStep out = step(before, onCarriage, onTracks, offCorridor, belowBedrock);
            STATES.put(player.getUUID(), out.next());

            if (out.landedOnTracks()) {
                ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "landed_on_tracks");
            }
            if (out.returnedToTrain()) {
                ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "returned_to_train");
            }
            if (out.leftTrain()) {
                ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, "left_train");
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

    /**
     * Per-player transition state for the leave/return state machine.
     *
     * @param wasOnTrainOrTracks      has been on a carriage or the tracks at least once — latches
     *                                {@code left_train}
     * @param hasBeenAboard           has been on a carriage at least once — gates the return marker
     * @param offCarriageScans        consecutive off-carriage scans since last aboard
     * @param leftCarriageSinceAboard departed a carriage (past the grace) and not yet re-boarded
     */
    record State(boolean wasOnTrainOrTracks, boolean hasBeenAboard,
                 int offCarriageScans, boolean leftCarriageSinceAboard) {

        /** A player nothing has been observed about yet. */
        static final State INITIAL = new State(false, false, 0, false);
    }

    /**
     * Which markers one scan concludes, plus the state to carry into the next. A field is
     * {@code true} only on the scan that earns it — re-firing an already-granted id would be a
     * vanilla no-op anyway, but this keeps the decision honest and table-testable.
     */
    record PresenceStep(boolean landedOnTracks, boolean returnedToTrain, boolean leftTrain,
                        State next) {}

    /**
     * The whole leave/return decision for one scan of one player — pure, so
     * {@code TrackPresenceStepTest} can table-test it without a Minecraft bootstrap. The three
     * position reads are supplied by the caller (and verified in-game); this pins only what a given
     * observation means. Mirrors {@link PlayerMobAdvancementEvents#step}.
     *
     * @param belowBedrock the player is under the world's bedrock layer — the basement the portal
     *                     system builds in. See the class javadoc for why this freezes the state
     *                     rather than resetting it.
     */
    static PresenceStep step(State s, boolean onCarriage, boolean onTracks,
                             boolean offCorridor, boolean belowBedrock) {
        if (belowBedrock) return new PresenceStep(false, false, false, s);

        boolean wasOnTrainOrTracks = s.wasOnTrainOrTracks() || onCarriage || onTracks;

        if (onCarriage) {
            return new PresenceStep(onTracks, s.leftCarriageSinceAboard(), false,
                new State(wasOnTrainOrTracks, true, 0, false));
        }

        int offScans = s.offCarriageScans();
        boolean left = s.leftCarriageSinceAboard();
        if (s.hasBeenAboard()) {
            offScans++;
            if (offScans >= OFF_GRACE_SCANS) left = true;
        }
        return new PresenceStep(onTracks, false, wasOnTrainOrTracks && offCorridor,
            new State(wasOnTrainOrTracks, s.hasBeenAboard(), offScans, left));
    }
}
