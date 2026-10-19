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

    private FarWind() {}

    static boolean exposed(Trains.Carriage carriage, Entity e) {
        TrainTransformProvider provider = carriage.provider();
        CarriageDims dims = provider.dims();
        int groupSize = provider.getGroupSize();
        int halfPadLen = groupSize > 1 ? CarriagePlacer.halfPadLen(dims) : 0;
        BlockPos origin = provider.getShipyardOrigin();

        Vector3d local = new Vector3d(e.getX(), e.getY(), e.getZ());
        carriage.ship().worldToShip(local);
        double roofTop = origin.getY() + dims.height();
        if (local.y < origin.getY() - FLOOR_TOLERANCE) return false;
        if (local.y < roofTop - ROOF_EPS) {
            double enclosedMinX = origin.getX() + halfPadLen;
            int slot = (int) Math.floor((local.x - enclosedMinX) / dims.length());
            if (slot >= 0 && slot < groupSize && walled(provider.getPIdx() + slot)) return false;
        }
        return !underTerrain(e.level(), e);
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
