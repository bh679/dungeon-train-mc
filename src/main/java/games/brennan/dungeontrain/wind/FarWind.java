package games.brennan.dungeontrain.wind;

import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.PlacedCarriageFacts;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import games.brennan.dungeontrain.train.Trains;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

/**
 * The cheap exposure test for things more than {@code WindServer.WIND_ROOMS} carriages from any player, where
 * nobody is watching closely: <b>is it inside a carriage with walls, yes or no?</b>
 *
 * <p>Pure arithmetic on the carriage layout (the same geometry {@code RoofRunEvents} uses) plus one lookup of
 * what the carriage was built as — no block scans for cover, lees or overhangs, apart from a 4-block terrain
 * check overhead so nothing is blown off inside a tunnel:</p>
 * <ul>
 *   <li>at or above roof height — exposed (on the roof);</li>
 *   <li>in the body of an enclosed carriage — sheltered; a flatbed's body is open, so exposed;</li>
 *   <li>on a coupling pad between groups — exposed;</li>
 *   <li>below the carriage floor — not on the train, left alone.</li>
 * </ul>
 */
final class FarWind {

    private static final double ROOF_EPS = 0.6;
    private static final double FLOOR_TOLERANCE = 1.0;
    private static final int TUNNEL_SCAN = 4;
    /** Slack past a group's own footprint before it stops being this group's business. */
    private static final double EDGE = 1.0;

    private FarWind() {}

    /** Out in the wind on this group, sheltered in it, or not on this group at all. */
    enum Verdict { EXPOSED, SHELTERED, NOT_HERE }

    static boolean exposed(Trains.Carriage carriage, Entity e) {
        return classify(carriage, e) == Verdict.EXPOSED;
    }

    static Verdict classify(Trains.Carriage carriage, Entity e) {
        TrainTransformProvider provider = carriage.provider();
        CarriageDims dims = provider.dims();
        int groupSize = provider.getGroupSize();
        int halfPadLen = groupSize > 1 ? CarriagePlacer.halfPadLen(dims) : 0;
        BlockPos origin = provider.getShipyardOrigin();

        Vector3d local = new Vector3d(e.getX(), e.getY(), e.getZ());
        carriage.ship().worldToShip(local);
        double roofTop = origin.getY() + dims.height();
        double groupLength = groupSize * (double) dims.length() + 2.0 * halfPadLen;
        // Not over this group at all (a neighbour's box reaches it): let the group it is on decide.
        if (local.x < origin.getX() - EDGE || local.x > origin.getX() + groupLength + EDGE) return Verdict.NOT_HERE;
        if (local.z < origin.getZ() - EDGE || local.z > origin.getZ() + dims.width() + EDGE) return Verdict.NOT_HERE;
        if (local.y < origin.getY() - FLOOR_TOLERANCE) return Verdict.NOT_HERE;
        if (local.y < roofTop - ROOF_EPS) {
            double enclosedMinX = origin.getX() + halfPadLen;
            int slot = (int) Math.floor((local.x - enclosedMinX) / dims.length());
            if (slot >= 0 && slot < groupSize && walled(provider.getPIdx() + slot)) return Verdict.SHELTERED;
        }
        return underTerrain(e.level(), e) ? Verdict.SHELTERED : Verdict.EXPOSED;
    }

    /** Why {@link #classify} decided as it did, for {@code debug wind trace}. */
    static String explain(Trains.Carriage carriage, Entity e) {
        TrainTransformProvider provider = carriage.provider();
        CarriageDims dims = provider.dims();
        int groupSize = provider.getGroupSize();
        int halfPadLen = groupSize > 1 ? CarriagePlacer.halfPadLen(dims) : 0;
        BlockPos origin = provider.getShipyardOrigin();
        Vector3d local = new Vector3d(e.getX(), e.getY(), e.getZ());
        carriage.ship().worldToShip(local);
        double rel = local.x - (origin.getX() + halfPadLen);
        int slot = (int) Math.floor(rel / dims.length());
        PlacedCarriageFacts.Facts facts = PlacedCarriageFacts.get(provider.getPIdx() + slot);
        return String.format(java.util.Locale.ROOT,
                "local(%.1f,%.1f,%.1f) origin(%d,%d,%d) dims(%dx%dx%d) group=%d pad=%d slot=%d pIdx=%d variant=%s",
                local.x, local.y, local.z, origin.getX(), origin.getY(), origin.getZ(), dims.length(), dims.height(),
                dims.width(), groupSize, halfPadLen, slot, provider.getPIdx() + slot,
                facts == null ? "?" : facts.variantId());
    }

    /** A carriage index built with walls — anything but a flatbed. Unknown counts as walled (stays put). */
    private static boolean walled(int pIdx) {
        PlacedCarriageFacts.Facts facts = PlacedCarriageFacts.get(pIdx);
        return facts == null || !CarriagePlacer.CarriageType.FLATBED.id().equals(facts.variantId());
    }

    private static boolean underTerrain(Level level, Entity e) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(e.getX(), e.getEyeY(), e.getZ()).mutable();
        for (int dy = 1; dy <= TUNNEL_SCAN; dy++) {
            pos.move(0, 1, 0);
            BlockState s = level.getBlockState(pos);
            if (!s.isAir() && s.blocksMotion()) return true;
        }
        return false;
    }
}
