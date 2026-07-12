package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3d;
import org.joml.primitives.AABBdc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Awards the "Roof Runner" advancement: travel the length of N carriage
 * groups along the train's exterior top in one continuous run, without ever
 * dropping inside an enclosed carriage.
 *
 * <h2>What counts as "on the roof"</h2>
 * The train is a string of Sable sub-levels, each a
 * {@code [back half-pad | groupSize enclosed carriages | front half-pad]}
 * group. The enclosed carriages have a glass roof; the half-pad couplings
 * between groups are open flatbeds sitting ~{@code height} blocks lower. So
 * a player running the train cannot stay at roof height across a coupling —
 * they drop to the pad deck and climb back up. "Without going below the
 * roof" therefore means <em>never entering an enclosed carriage interior</em>:
 * standing on a roof, on a coupling/pad deck, or airborne above the train all
 * count as on-top; only dropping <em>inside</em> an enclosed carriage (or off
 * the train entirely) ends the run.
 *
 * <h2>Detection</h2>
 * Each scan, for the group whose world AABB (loosely) contains the player, we
 * transform the player's feet into that ship's model space via
 * {@link games.brennan.dungeontrain.ship.ManagedShip#worldToShip} and compare
 * against the known sub-level layout (shipyard origin + {@code halfPadLen} +
 * dims). Model-Y is the train's up axis (trains only yaw), so the roof line is
 * a fixed model-space plane regardless of where the train is in the world.
 *
 * <h2>Progress</h2>
 * Per player we track the min and max group-anchor pIdx reached during the
 * current uninterrupted on-roof streak; {@code groups = (max − min) /
 * groupSize}. The streak is "continuous" only if it is touched on consecutive
 * scans ({@link #SCAN_PERIOD_TICKS} apart) — any gap (dropping inside, falling
 * off, changing dimension, disconnecting) restarts it. Transient per-player
 * state only; advancement completion is persisted by vanilla, so nothing here
 * touches {@link games.brennan.dungeontrain.player.PlayerRunState}.
 *
 * <p>Tracked per player (every boarded player can earn it independently),
 * mirroring the per-level scan cadence of {@link BoardingProgressEvents}.</p>
 */
public final class RoofRunEvents {

    /** Per-level scan period, in ticks. Matches {@link BoardingProgressEvents}. */
    private static final int SCAN_PERIOD_TICKS = 10;

    /**
     * Horizontal slack (blocks) on group footprints, to bridge coupling seams and
     * include players on edge stairs or blocks placed immediately adjacent to the
     * carriage sides. Also used for the cheap world-space pre-filter.
     */
    private static final double HORIZONTAL_PADDING = 2.0;

    /**
     * Extra tolerance (blocks) on each X end of the enclosed section when
     * deciding whether a <em>roof-height</em> player counts as "over enclosed."
     * Catches players standing on stair blocks at the roof ends, which may sit
     * 1 block past the enclosedMinX/enclosedMaxX boundary.
     */
    private static final double ENCLOSED_END_PADDING = 1.0;

    /**
     * How far below a roof's top face (model-Y) the player's feet may be and
     * still count as "on the roof" — absorbs the block-top vs feet offset and
     * float noise. Comfortably smaller than the gap to an enclosed-interior
     * stance, so a player jumping <em>inside</em> a carriage never reads as
     * on-roof.
     */
    private static final double ROOF_EPS = 0.6;

    /** How far below the floor (model-Y) still counts as "aboard" vs fallen off. */
    private static final double FLOOR_TOLERANCE = 1.0;

    /** World-Y headroom above a group's AABB top kept in the cheap pre-filter (covers jumps). */
    private static final double JUMP_HEADROOM = 3.0;

    /** Per-player in-progress roof run. Presence ⇒ a streak is active. */
    private static final Map<UUID, Streak> STREAKS = new HashMap<>();

    private RoofRunEvents() {}

        public static void onLevelTick(net.minecraft.world.level.Level tickedLevel) {
        if (!(tickedLevel instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        if (now % SCAN_PERIOD_TICKS != 0) return;

        List<Trains.Carriage> carriages = Trains.allCarriages(level);
        if (carriages.isEmpty()) return;

        for (ServerPlayer player : level.players()) {
            UUID id = player.getUUID();
            RoofStatus status = classify(carriages, player);
            switch (status.kind()) {
                case ON_ROOF -> {
                    int groupSize = Math.max(1, status.groupSize());
                    Streak s = STREAKS.get(id);
                    if (s == null || s.lastScan != now - SCAN_PERIOD_TICKS) {
                        // No prior streak, or a gap broke continuity → start fresh.
                        s = new Streak(status.anchorPIdx(), now, groupSize);
                        STREAKS.put(id, s);
                    } else {
                        s.min = Math.min(s.min, status.anchorPIdx());
                        s.max = Math.max(s.max, status.anchorPIdx());
                        s.lastScan = now;
                        s.groupSize = groupSize;
                    }
                    int groups = (s.max - s.min) / groupSize;
                    if (groups > 0) {
                        ModAdvancementTriggers.ROOF_RUN_GROUPS.get().trigger(player, groups);
                    }
                }
                case ON_TOP_OTHER -> {
                    // On a pad deck / airborne over the top: keep an existing streak
                    // alive across the coupling. Also fires the trigger with groups+1
                    // so that traversing one enclosed group roof (then landing on the
                    // pad) satisfies threshold=1 without needing to climb a second roof.
                    Streak s = STREAKS.get(id);
                    if (s != null && s.lastScan == now - SCAN_PERIOD_TICKS) {
                        int gs = Math.max(1, s.groupSize);
                        ModAdvancementTriggers.ROOF_RUN_GROUPS.get().trigger(player, (s.max - s.min) / gs + 1);
                        s.lastScan = now;
                    }
                }
                case BELOW_ROOF, OFF -> STREAKS.remove(id);
            }
        }

        // Drop streaks for players who have fully disconnected, so the map
        // doesn't leak. (Dimension changes / off-roof are handled by the
        // lastScan continuity check above.)
        STREAKS.keySet().removeIf(uuid -> level.getServer().getPlayerList().getPlayer(uuid) == null);
    }

    /**
     * Classify the player's position against every loaded group, returning the
     * highest-priority status: {@code ON_ROOF > BELOW_ROOF > ON_TOP_OTHER >
     * OFF}. BELOW_ROOF outranks ON_TOP_OTHER so a player genuinely inside a
     * carriage near a seam resets even if a neighbour group's padded footprint
     * also (loosely) contains them.
     */
    private static RoofStatus classify(List<Trains.Carriage> carriages, ServerPlayer player) {
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        RoofStatus best = RoofStatus.OFF;

        for (Trains.Carriage c : carriages) {
            AABBdc bb = c.ship().worldAABB();
            // Cheap world-space pre-filter (the world AABB of a yawed ship is
            // conservatively large, so this only over-includes; the precise
            // test runs in model space below).
            if (px < bb.minX() - HORIZONTAL_PADDING || px > bb.maxX() + HORIZONTAL_PADDING) continue;
            if (pz < bb.minZ() - HORIZONTAL_PADDING || pz > bb.maxZ() + HORIZONTAL_PADDING) continue;
            if (py < bb.minY() - FLOOR_TOLERANCE || py > bb.maxY() + JUMP_HEADROOM) continue;

            TrainTransformProvider provider = c.provider();
            CarriageDims dims = provider.dims();
            int groupSize = provider.getGroupSize();
            int length = dims.length();
            int height = dims.height();
            int width = dims.width();
            boolean wrapWithPads = groupSize > 1;
            int halfPadLen = wrapWithPads ? CarriagePlacer.halfPadLen(dims) : 0;

            BlockPos so = provider.getShipyardOrigin();
            double originX = so.getX();
            double originY = so.getY();
            double originZ = so.getZ();
            double subLevelLength = wrapWithPads ? (groupSize * length + 2.0 * halfPadLen) : length;
            double enclosedMinX = originX + halfPadLen;
            double enclosedMaxX = enclosedMinX + groupSize * (double) length;
            double roofTopY = originY + height;

            // Precise model-space position (un-rotated shipyard frame).
            Vector3d local = new Vector3d(px, py, pz);
            c.ship().worldToShip(local);

            boolean withinX = local.x >= originX - HORIZONTAL_PADDING
                && local.x <= originX + subLevelLength + HORIZONTAL_PADDING;
            boolean withinZ = local.z >= originZ - HORIZONTAL_PADDING
                && local.z <= originZ + width + HORIZONTAL_PADDING;
            if (!withinX || !withinZ) continue;

            // Strict X range: used for the below-roof check so players approaching
            // from the coupling pad floor don't inadvertently get BELOW_ROOF.
            boolean overEnclosed = local.x >= enclosedMinX && local.x <= enclosedMaxX;
            // Wider X range: used at roof height so players on edge stairs or
            // blocks placed 1 block past the enclosed ends still count as ON_ROOF.
            boolean overEnclosedOrEdge = local.x >= enclosedMinX - ENCLOSED_END_PADDING
                && local.x <= enclosedMaxX + ENCLOSED_END_PADDING;

            RoofStatus s;
            if (local.y >= roofTopY - ROOF_EPS) {
                // At or above roof height: on the glass roof (over enclosed) or
                // airborne over a pad/coupling.
                s = overEnclosedOrEdge
                    ? RoofStatus.onRoof(provider.getPIdx(), groupSize)
                    : RoofStatus.ON_TOP_OTHER;
            } else if (local.y >= originY - FLOOR_TOLERANCE) {
                // Down in the body: inside an enclosed carriage (below the roof)
                // or standing on an open pad deck (still on top).
                s = overEnclosed ? RoofStatus.BELOW_ROOF : RoofStatus.ON_TOP_OTHER;
            } else {
                s = RoofStatus.OFF;
            }

            if (s.rank() > best.rank()) best = s;
        }
        return best;
    }

    /** Mutable per-player streak: min/max group-anchor pIdx + last-touched scan tick. */
    private static final class Streak {
        int min;
        int max;
        long lastScan;
        int groupSize;

        Streak(int anchor, long scan, int groupSize) {
            this.min = anchor;
            this.max = anchor;
            this.lastScan = scan;
            this.groupSize = groupSize;
        }
    }

    private enum Kind { OFF, ON_TOP_OTHER, BELOW_ROOF, ON_ROOF }

    /** Outcome of classifying a player against one (or all) groups. */
    private record RoofStatus(Kind kind, int anchorPIdx, int groupSize) {
        static final RoofStatus OFF = new RoofStatus(Kind.OFF, 0, 1);
        static final RoofStatus ON_TOP_OTHER = new RoofStatus(Kind.ON_TOP_OTHER, 0, 1);
        static final RoofStatus BELOW_ROOF = new RoofStatus(Kind.BELOW_ROOF, 0, 1);

        static RoofStatus onRoof(int anchorPIdx, int groupSize) {
            return new RoofStatus(Kind.ON_ROOF, anchorPIdx, groupSize);
        }

        int rank() {
            return kind.ordinal();
        }
    }
}
