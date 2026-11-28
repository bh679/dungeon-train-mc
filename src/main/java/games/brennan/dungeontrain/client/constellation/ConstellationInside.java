package games.brennan.dungeontrain.client.constellation;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import games.brennan.dungeontrain.block.ConstellationBlocks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;

/**
 * Is the player standing in a constellation block, in the world or on a carriage?
 *
 * <p>"In" means any part of the player's box overlaps one, so a single block at foot level counts.
 * The box is sampled at its four bottom/middle/top corners and its centre line rather than walked
 * block by block: the box is 0.6 wide, so those points touch every block it can overlap.</p>
 *
 * <p><b>Carriages:</b> a carriage's blocks live in its Sable plot, far away and only reachable through
 * the plot's own chunks (not {@code ClientLevel#getBlockState}). For each carriage whose box is near
 * the player, the sample points are carried into its plot with the inverse of its render pose and read
 * from its chunks, the same way {@code SkyboxBlockIndex} reads carriage skybox blocks.</p>
 */
final class ConstellationInside {

    private static final double INSET = 0.02;

    private ConstellationInside() {}

    static boolean test(ClientLevel level, Player player, float partialTick) {
        if (!ConstellationBlocks.active()) return false;
        Vec3[] points = samplePoints(player, partialTick);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Vec3 p : points) {
            cursor.set(p.x, p.y, p.z);
            if (ConstellationBlocks.isLive(level.getBlockState(cursor))) return true;
        }
        return onCarriage(level, points, partialTick, cursor);
    }

    /** Twelve points on the box's edges (4 corners x bottom/middle/top) and three on its centre line. */
    static Vec3[] samplePoints(Player player, float partialTick) {
        Vec3 feet = player.getPosition(partialTick);
        double half = player.getBbWidth() / 2.0 - INSET;
        double h = player.getBbHeight();
        double[] ys = {INSET, h / 2.0, h - INSET};
        Vec3[] out = new Vec3[15];
        int i = 0;
        for (double y : ys) {
            out[i++] = feet.add(0, y, 0);
            out[i++] = feet.add(-half, y, -half);
            out[i++] = feet.add(half, y, -half);
            out[i++] = feet.add(-half, y, half);
            out[i++] = feet.add(half, y, half);
        }
        return out;
    }

    private static boolean onCarriage(ClientLevel level, Vec3[] points, float partialTick,
                                      BlockPos.MutableBlockPos cursor) {
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return false;
        AABB near = new AABB(points[0], points[points.length - 1]).inflate(3.0);
        Vector3d local = new Vector3d();
        for (ClientSubLevel sub : container.getAllSubLevels()) {
            BoundingBox3dc box = sub.boundingBox();
            if (box == null || !box.intersects(near)) continue;
            Pose3dc pose = sub.renderPose(partialTick);
            LevelPlot plot = sub.getPlot();
            if (pose == null || plot == null) continue;
            Map<Long, LevelChunk> chunks = null;
            for (Vec3 p : points) {
                local.set(p.x, p.y, p.z);
                pose.transformPositionInverse(local); // single-arg form: maps in place
                cursor.set(local.x, local.y, local.z);
                if (chunks == null) chunks = chunksOf(plot);
                LevelChunk chunk = chunks.get(ChunkPos.asLong(cursor.getX() >> 4, cursor.getZ() >> 4));
                if (chunk != null && ConstellationBlocks.isLive(chunk.getBlockState(cursor))) return true;
            }
        }
        return false;
    }

    private static Map<Long, LevelChunk> chunksOf(LevelPlot plot) {
        Map<Long, LevelChunk> chunks = new HashMap<>();
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk != null) chunks.put(chunk.getPos().toLong(), chunk);
        }
        return chunks;
    }
}
