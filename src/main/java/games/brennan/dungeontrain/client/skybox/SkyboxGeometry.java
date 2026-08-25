package games.brennan.dungeontrain.client.skybox;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;

/**
 * Emits the depth-punch quads for skybox cubes.
 *
 * <p>Vertices are supplied as <em>camera-relative world coordinates</em> and
 * transformed by the frustum matrix (camera rotation only) — the same convention
 * vanilla's chunk layers use. Camera subtraction happens in {@code double} and only
 * the small result is narrowed to {@code float}: Sable plot chunks sit far from the
 * origin, so folding the camera offset into a {@link Matrix4f} would lose precision
 * exactly where the carriage geometry is.</p>
 *
 * <p>Cube corners are indexed as a 3-bit field — bit 0 = +X, bit 1 = +Y, bit 2 = +Z —
 * so a face is a fixed quad of corner indices and works unchanged whether the corners
 * came straight from a {@code BlockPos} or through a rotating carriage's pose.</p>
 */
public final class SkyboxGeometry {

    /**
     * Corner indices per face, ordered by {@link Direction#get3DDataValue()}
     * (DOWN, UP, NORTH, SOUTH, WEST, EAST). Winding is consistent but not relied
     * upon — the renderer draws with culling disabled.
     */
    private static final int[][] FACE_CORNERS = {
        {0, 4, 5, 1}, // DOWN   (y = 0)
        {2, 3, 7, 6}, // UP     (y = 1)
        {0, 1, 3, 2}, // NORTH  (z = 0)
        {4, 6, 7, 5}, // SOUTH  (z = 1)
        {0, 2, 6, 4}, // WEST   (x = 0)
        {1, 5, 7, 3}, // EAST   (x = 1)
    };

    private SkyboxGeometry() {}

    /** {@code true} if corner {@code index} is on the {@code +X} side of the cube. */
    public static int cornerDx(int index) {
        return index & 1;
    }

    public static int cornerDy(int index) {
        return (index >> 1) & 1;
    }

    public static int cornerDz(int index) {
        return (index >> 2) & 1;
    }

    /**
     * Emit the enabled faces of one cube.
     *
     * @param cornersX world-space X of each of the 8 corners, indexed as above
     * @param faceMask bit per {@link Direction#get3DDataValue()}; set = draw that face
     * @return number of quads written
     */
    public static int emitCube(BufferBuilder builder, Matrix4f frustum,
                               double[] cornersX, double[] cornersY, double[] cornersZ,
                               double camX, double camY, double camZ,
                               int faceMask) {
        int quads = 0;
        for (int face = 0; face < FACE_CORNERS.length; face++) {
            if ((faceMask & (1 << face)) == 0) continue;
            for (int corner : FACE_CORNERS[face]) {
                builder.addVertex(frustum,
                    (float) (cornersX[corner] - camX),
                    (float) (cornersY[corner] - camY),
                    (float) (cornersZ[corner] - camZ));
            }
            quads++;
        }
        return quads;
    }
}
