package games.brennan.dungeontrain.tunnel;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The cells of a tunnel template the train drives through — the box an author must leave empty.
 *
 * <p>Worldgen clears the carriage envelope ({@code TrackGenerator#placeTracksForChunk}) <i>before</i>
 * the tunnel is stamped, so anything a template places inside this box survives into the world and
 * stands in the train's path. The editor washes such blocks red; this class owns where "inside" is.</p>
 *
 * <h2>Where the numbers come from</h2>
 * <ul>
 *   <li><b>Y</b> — the stamp origin is {@link TunnelGeometry#floorY()} = the track bed. The rails sit
 *       one above ({@code TrackGeometry.railY = trainY - 1}) and the carriage floor one above that,
 *       so the body occupies local {@code y = 2 .. 2 + height - 1}.</li>
 *   <li><b>Z</b> — {@code TunnelGenerator} stamps at {@code wallMinZ + 1 = trackZMin - 3}, and the
 *       track spans the train's width, so the body occupies local {@code z = 3 .. 3 + width - 1}.</li>
 *   <li><b>X</b> — the whole section; the train runs straight through it.</li>
 * </ul>
 * <p>Both ranges are clipped to the template's {@link TunnelPlacer#HEIGHT} × {@link TunnelPlacer#WIDTH}
 * box, so an unusually large train still yields a box inside the plot.</p>
 */
public final class TunnelTrainEnvelope {

    /** Local Y of the carriage floor: bed at 0, rails at 1. */
    public static final int FLOOR_Y = 2;
    /** Local Z of the train's first column — the stamp origin sits three blocks outside the track. */
    public static final int NEAR_Z = 3;

    private TunnelTrainEnvelope() {}

    /** The envelope in template-local coordinates for a train of {@code dims}. */
    public static BoundingBox localBox(CarriageDims dims) {
        int maxY = Math.min(TunnelPlacer.HEIGHT - 1, FLOOR_Y + dims.height() - 1);
        int maxZ = Math.min(TunnelPlacer.WIDTH - 1, NEAR_Z + dims.width() - 1);
        return new BoundingBox(0, FLOOR_Y, NEAR_Z, TunnelPlacer.LENGTH - 1, maxY, maxZ);
    }

    /** The envelope in world coordinates for a template plot whose min corner is {@code origin}. */
    public static BoundingBox worldBox(BlockPos origin, CarriageDims dims) {
        return localBox(dims).moved(origin.getX(), origin.getY(), origin.getZ());
    }
}
