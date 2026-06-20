package games.brennan.dungeontrain.client.snapshot;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
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

    /**
     * Count how many carriages have their block data loaded — a non-zero AABB (the sub-level has
     * had its first physics tick) <em>and</em> at least one loaded plot chunk. Same two checks
     * {@link CarriageOcclusion#gatherNearby} uses. Stops counting at {@code cap} so it touches only
     * a few sub-levels.
     *
     * <p>Sable streams sub-levels nearest-first, so the first carriages to report data are the ones
     * around the player. NOTE this reflects block <em>data</em>, not the render mesh — the visible
     * geometry builds a beat later — so callers that need the train to be <em>visible</em> (the
     * arrival snapshot) pair this with a short settle delay. No per-carriage distance math (cheapest).</p>
     */
    public static int countLoadedCarriages(ClientLevel level, int cap) {
        if (cap <= 0) return 0;
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return 0;
        int loaded = 0;
        for (ClientSubLevel sub : container.getAllSubLevels()) {
            BoundingBox3dc box = sub.boundingBox();
            if (box == null) continue;
            // Fresh sub-levels report a zero AABB before their first physics tick — not yet loaded.
            if (box.minX() == 0 && box.minY() == 0 && box.minZ() == 0
                && box.maxX() == 0 && box.maxY() == 0 && box.maxZ() == 0) continue;
            if (!hasLoadedChunk(sub)) continue;
            if (++loaded >= cap) return loaded;
        }
        return loaded;
    }

    /** Does this carriage's plot have at least one loaded chunk (blocks present to render)? */
    private static boolean hasLoadedChunk(ClientSubLevel sub) {
        LevelPlot plot = sub.getPlot();
        if (plot == null) return false;
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk != null) return true;
        }
        return false;
    }
}
