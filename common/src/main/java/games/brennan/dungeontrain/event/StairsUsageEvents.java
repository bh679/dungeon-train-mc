package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.world.StairsLocationData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Awards {@code used_pillar_stairs} / {@code used_tunnel_stairs}: fires when a
 * player stands inside a stair structure that worldgen recorded into
 * {@link StairsLocationData} (bridge-pillar side stairs vs. tunnel stairwell).
 *
 * <p>Each scan, for every player, we pull the handful of recorded boxes within
 * {@link #X_WINDOW} of the player's X (the index is keyed by X for exactly this
 * pruning) and test containment. Both advancements are one-shot markers, so
 * firing the same id repeatedly while the player lingers on the steps is
 * harmless. Stateless — no per-player bookkeeping to maintain.</p>
 *
 * <p>Per-dimension: queries the ticking level's own {@link StairsLocationData},
 * matching where {@code TrackGenerator} recorded the boxes. Throttled to once
 * every {@link #SCAN_PERIOD_TICKS} ticks, like the other train scanners.</p>
 */
public final class StairsUsageEvents {

    /** Per-level scan period, in ticks. Matches the other train scanners. */
    private static final int SCAN_PERIOD_TICKS = 10;

    /**
     * Half-width (blocks) of the X window queried around each player. Stair
     * placements are &gt;= 100 blocks apart and at most 5 wide, so 32 comfortably
     * covers any box that could contain the player while keeping the scan cheap.
     */
    private static final int X_WINDOW = 32;

    /** Containment slack (blocks) so walking the steps reliably reads as inside. */
    private static final double CONTAINS_PAD = 0.5;

    private StairsUsageEvents() {}

        public static void onLevelTick(net.minecraft.world.level.Level tickedLevel) {
        if (!(tickedLevel instanceof ServerLevel level)) return;
        if (level.getGameTime() % SCAN_PERIOD_TICKS != 0) return;

        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) return;

        StairsLocationData data = StairsLocationData.get(level);

        for (ServerPlayer player : players) {
            double px = player.getX();
            double py = player.getY();
            double pz = player.getZ();

            List<StairsLocationData.Box> boxes = data.near((int) Math.floor(px), X_WINDOW);
            if (boxes.isEmpty()) continue;

            for (StairsLocationData.Box box : boxes) {
                if (!box.contains(px, py, pz, CONTAINS_PAD)) continue;
                ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, actionId(box.kind()));
            }
        }
    }

    private static String actionId(StairsLocationData.Kind kind) {
        return switch (kind) {
            case PILLAR_STAIRS -> "used_pillar_stairs";
            case TUNNEL_STAIRS -> "used_tunnel_stairs";
        };
    }
}
