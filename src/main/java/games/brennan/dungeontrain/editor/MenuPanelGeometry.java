package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Where a world-space editor panel hangs and which way is up for it — the sums the block-variant,
 * container-contents and template-blocks menus each used to carry a copy of.
 */
public final class MenuPanelGeometry {

    /** How far off a block face the panel sits, so it never z-fights the block. */
    static final double FACE_LIFT = 0.02;

    private MenuPanelGeometry() {}

    /**
     * The panel's up-axis for a block face: vertical faces (floor, ceiling) snap to the player's
     * current horizontal look so the panel reads the right way up; side faces use world up.
     * Callers capture it once at open time and freeze it so re-syncs do not rotate the panel.
     */
    public static Vec3 computeUp(Direction face, Player player) {
        if (face.getAxis() != Direction.Axis.Y) return new Vec3(0, 1, 0);
        return horizontalLook(player);
    }

    /** As {@link #computeUp(Direction, Player)} for a free normal: near-vertical normals snap. */
    public static Vec3 computeUp(Vec3 normal, Player player) {
        if (Math.abs(normal.y) > 0.99) return horizontalLook(player);
        return new Vec3(0, 1, 0);
    }

    /** The player's look flattened to the horizontal, or +Z when they look straight up or down. */
    static Vec3 horizontalLook(Player player) {
        Vec3 look = player.getLookAngle();
        return horizontalOf(look);
    }

    /** Pure form of {@link #horizontalLook}. */
    static Vec3 horizontalOf(Vec3 look) {
        Vec3 horizontal = new Vec3(look.x, 0, look.z);
        if (horizontal.lengthSqr() < 1.0e-6) horizontal = new Vec3(0, 0, 1);
        return horizontal.normalize();
    }

    /** Clamp each axis of {@code localPos} into {@code [0, footprint)} so a cell lookup always lands on a real cell. */
    public static BlockPos clampToFootprint(BlockPos localPos, BlockVariantPlot plot) {
        return clampToFootprint(localPos, plot.footprint());
    }

    static BlockPos clampToFootprint(BlockPos localPos, Vec3i f) {
        int x = Math.max(0, Math.min(f.getX() - 1, localPos.getX()));
        int y = Math.max(0, Math.min(f.getY() - 1, localPos.getY()));
        int z = Math.max(0, Math.min(f.getZ() - 1, localPos.getZ()));
        return new BlockPos(x, y, z);
    }

    /** The unit normal of {@code face}. */
    public static Vec3 normalOf(Direction face) {
        return new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
    }

    /** The centre of {@code face} of the block at {@code worldPos}, lifted {@link #FACE_LIFT} off it. */
    public static Vec3 faceAnchor(BlockPos worldPos, Direction face) {
        Vec3 normal = normalOf(face);
        Vec3 faceCentre = new Vec3(
            worldPos.getX() + 0.5 + face.getStepX() * 0.5,
            worldPos.getY() + 0.5 + face.getStepY() * 0.5,
            worldPos.getZ() + 0.5 + face.getStepZ() * 0.5);
        return faceCentre.add(normal.scale(FACE_LIFT));
    }

    /** The panel's right-axis for an {@code up} and the face {@code normal} it hangs on. */
    public static Vec3 right(Vec3 up, Vec3 normal) {
        return up.cross(normal).normalize();
    }
}
