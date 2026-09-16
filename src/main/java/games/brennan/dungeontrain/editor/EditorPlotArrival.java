package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Where a player lands when they enter a plot from above: on the roof, a few blocks in front of
 * the plot's world-space menu, facing it.
 *
 * <p>Every editor's {@code enter(…, onTop=true)} used to drop the player at the footprint centre
 * with whatever heading they arrived with — on a long carriage that is half a plot from the menu,
 * as often as not behind them. The menu anchors at {@link EditorPlotLabels#anchorAbove}, so the
 * landing spot is derived from that same anchor: the two cannot drift apart.</p>
 */
public record EditorPlotArrival(double x, double y, double z, float yaw, float pitch) {

    /** Blocks to stand back from the menu, along -X, so it is readable rather than in your face. */
    static final int STANDOFF = 3;

    /** Minecraft yaw that looks along +X — toward the menu, which sits on the plot's +X edge. */
    private static final float FACING_POSITIVE_X = -90f;

    /**
     * The spot on the roof of the plot at {@code origin} with the given {@code footprint}
     * (length, height, width) from which its menu is straight ahead.
     *
     * <p>Clamped to the plot's own -X edge so a short plot — a pillar section is one block long —
     * still lands the player on the roof rather than in the air beside it.</p>
     */
    public static EditorPlotArrival inFrontOfMenu(BlockPos origin, Vec3i footprint) {
        BlockPos anchor = EditorPlotLabels.anchorAbove(origin, footprint);
        double x = Math.max(origin.getX() + 0.5, anchor.getX() + 0.5 - STANDOFF);
        // Same roof height the centre landing used: one above the bedrock cage.
        double y = origin.getY() + footprint.getY() + 1.0;
        double z = anchor.getZ() + 0.5;
        return new EditorPlotArrival(x, y, z, FACING_POSITIVE_X, 0f);
    }

    /** Put {@code player} here. */
    public void teleport(ServerPlayer player, ServerLevel level) {
        player.teleportTo(level, x, y, z, yaw, pitch);
    }
}
