package games.brennan.dungeontrain.client.snapshot;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side lookup of the train carriages (Sable sub-levels) in world space,
 * via the same API path {@link games.brennan.dungeontrain.client.sound.TrainEngineSound}
 * uses: {@link SubLevelContainer#getContainer(ClientLevel)} →
 * {@link ClientSubLevelContainer#getAllSubLevels()}, each carriage exposing a
 * world-space AABB. The mod treats every Sable sub-level as a carriage.
 */
public final class NearestCarriage {

    private NearestCarriage() {}

    /** Is the player inside, or within {@code range} blocks of, any carriage AABB? */
    public static boolean playerAboardOrNear(ClientLevel level, Vec3 pos, double range) {
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return false;
        double rangeSq = range * range;
        for (ClientSubLevel sub : container.getAllSubLevels()) {
            BoundingBox3dc box = sub.boundingBox();
            if (box == null) continue;
            double minX = box.minX(), minY = box.minY(), minZ = box.minZ();
            double maxX = box.maxX(), maxY = box.maxY(), maxZ = box.maxZ();
            if (minX == 0 && minY == 0 && minZ == 0 && maxX == 0 && maxY == 0 && maxZ == 0) continue;
            double dx = pos.x < minX ? minX - pos.x : (pos.x > maxX ? pos.x - maxX : 0.0);
            double dy = pos.y < minY ? minY - pos.y : (pos.y > maxY ? pos.y - maxY : 0.0);
            double dz = pos.z < minZ ? minZ - pos.z : (pos.z > maxZ ? pos.z - maxZ : 0.0);
            if (dx * dx + dy * dy + dz * dz <= rangeSq) return true;
        }
        return false;
    }
}
