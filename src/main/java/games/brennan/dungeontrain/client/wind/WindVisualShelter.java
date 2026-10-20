package games.brennan.dungeontrain.client.wind;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import games.brennan.dungeontrain.ship.CarriageDeck;
import games.brennan.dungeontrain.wind.TrainExposure;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

/**
 * Where the wind's <em>visuals</em> may go: anywhere the wind actually blows, never inside a carriage or a
 * tunnel. Decided per streak and per point — not by where the player is — so a player sheltering inside a
 * carriage or a tunnel still sees the wind blowing past outside, and no streak drifts through their room.
 *
 * <ul>
 *   <li><b>Inside a carriage</b>: a carriage floor below the point within {@link #CARRIAGE_SCAN}, and either a
 *       roof above or the carriage's side walls around it (an open-roofed room still counts). Above a roof or
 *       under the train is open air.</li>
 *   <li><b>In a tunnel</b>: solid terrain within {@link #TERRAIN_SCAN} blocks overhead (same rule as the push,
 *       {@code TrainExposure#underTerrain}).</li>
 * </ul>
 *
 * <p>A Windy streak flies in a straight line at constant speed in the train's frame (DT adds the carriage's
 * velocity and turns friction off), so its whole path is known the moment it spawns: {@link #openTicks}
 * marches it and the streak's life is cut where it would first enter shelter.</p>
 */
final class WindVisualShelter {

    static final int CARRIAGE_SCAN = 7;
    static final int TERRAIN_SCAN = 12;
    /**
     * Path sampling step, ticks: the per-streak cost. Even a strong late gust streak (~1.5 blocks/tick relative)
     * moves about 6 blocks in 4 ticks, under a carriage room's width, so it still lands a sample inside any room.
     */
    private static final int STEP = 4;

    private WindVisualShelter() {}

    /** True when the wind does not reach world point {@code (x, y, z)}. */
    static boolean sheltered(ClientLevel level, double x, double y, double z) {
        return underTerrain(level, x, y, z) || insideCarriage(level, x, y, z);
    }

    /**
     * How many ticks a streak at {@code (x, y, z)} can live before it would enter shelter (≤ {@code lifetime};
     * 0 = it starts sheltered). {@code relVx/Vz} is its velocity relative to the carriage (the wind),
     * {@code carriageVx/Vz} the carriage's own: carriages are tested in their frame (the streak moves with the
     * train), terrain in the world's.
     */
    static int openTicks(ClientLevel level, double x, double y, double z, double relVx, double relVz,
                         double carriageVx, double carriageVz, int lifetime) {
        for (int t = 0; t <= lifetime; t += STEP) {
            double wx = x + (relVx + carriageVx) * t;
            double wz = z + (relVz + carriageVz) * t;
            if (underTerrain(level, wx, y, wz)) return t;
            if (insideCarriage(level, x + relVx * t, y, z + relVz * t)) return t;
        }
        return lifetime;
    }

    private static boolean underTerrain(ClientLevel level, double x, double y, double z) {
        BlockPos.MutableBlockPos pos = BlockPos.containing(x, y, z).mutable();
        for (int dy = 1; dy <= TERRAIN_SCAN; dy++) {
            pos.move(0, 1, 0);
            BlockState s = level.getBlockState(pos);
            if (!s.isAir() && s.blocksMotion()) return true;
        }
        return false;
    }

    private static boolean insideCarriage(ClientLevel level, double x, double y, double z) {
        BoundingBox3d probe = new BoundingBox3d(x - 0.5, y - 0.5, z - 0.5, x + 0.5, y + 0.5, z + 0.5);
        for (SubLevel sub : Sable.HELPER.getAllIntersecting(level, probe)) {
            if (sub.isRemoved()) continue;
            LevelPlot plot = sub.getPlot();
            if (plot == null) continue;
            Pose3dc pose = sub.logicalPose();
            Vector3d local = pose.transformPositionInverse(new Vector3d(x, y, z));
            BlockPos cell = BlockPos.containing(local.x, local.y, local.z);
            boolean floor = solidWithin(plot, cell, -1);
            if (floor && (solidWithin(plot, cell, 1) || TrainExposure.walledIn(plot, local))) return true;
        }
        return false;
    }

    private static boolean solidWithin(LevelPlot plot, BlockPos cell, int dir) {
        for (int d = 1; d <= CARRIAGE_SCAN; d++) {
            BlockState s = CarriageDeck.blockInPlot(plot, cell.above(d * dir));
            if (!s.isAir() && s.blocksMotion()) return true;
        }
        return false;
    }
}
