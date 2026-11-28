package games.brennan.dungeontrain.client.constellation;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Which blocks stand between the eye and a star of the constellation patch. Those blocks are hidden
 * whole: the patch shows through every pixel of them. Every other block draws as normal, even inside
 * the patch. A block is either in the way or not, never partway.
 *
 * <p>Rays run from the eye toward each drawn star (its centre and four points round its rim) for
 * {@link #REACH} blocks, through the main world and, carried into each nearby carriage's plot by the
 * inverse of its pose, through Sable carriages. A carriage block is kept in plot space and drawn later
 * with the carriage's render pose, so it stays on the moving train.</p>
 *
 * <p>Refreshed every {@link #INTERVAL_TICKS} ticks into an immutable list. Client thread only.</p>
 */
final class ConstellationOcclusion {

    /** How far along each ray blocks count. The patch is at sky distance; past this, nothing. */
    static final double REACH = 64.0;
    static final int INTERVAL_TICKS = 3;

    /** One hidden block: {@code subLevel} null for the main world, else that carriage's plot. */
    record Hidden(UUID subLevel, long pos) {}

    /** The widest a hidden block may push the patch's solid core, in radii. */
    static final float MAX_REACH = 2.4f;

    private static volatile List<Hidden> hidden = List.of();
    /** How far from the patch's centre, in radii, the hidden blocks reach: 0 with none. */
    private static volatile float reach;
    private static int tick;

    private ConstellationOcclusion() {}

    static List<Hidden> hidden() {
        return hidden;
    }

    static float reach() {
        return reach;
    }

    static void clear() {
        hidden = List.of();
        reach = 0f;
    }

    /** Once a client tick while the patch is open. */
    static void update(ClientLevel level, Vec3 eye, List<Vector3f> directions) {
        if (++tick % INTERVAL_TICKS != 0) return;
        Set<Hidden> out = new LinkedHashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Vector3f d : directions) {
            Vec3 end = eye.add(d.x * REACH, d.y * REACH, d.z * REACH);
            walk(eye, end, pos -> {
                cursor.set(pos[0], pos[1], pos[2]);
                if (blocks(level.getBlockState(cursor), level, cursor)) out.add(new Hidden(null, cursor.asLong()));
            });
        }
        carriages(level, eye, directions, out, cursor);
        hidden = List.copyOf(out);
        reach = reachOf(level, eye, hidden);
    }

    /**
     * The farthest any corner of a hidden block lands from the patch's centre, in radii, seen from the
     * eye: the patch's solid core grows to this so every hidden block is covered before the rim fades.
     */
    private static float reachOf(ClientLevel level, Vec3 eye, List<Hidden> blocks) {
        if (blocks.isEmpty()) return 0f;
        Map<UUID, Pose3dc> poses = new HashMap<>();
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null) {
            for (ClientSubLevel sub : container.getAllSubLevels()) {
                Pose3dc pose = sub.logicalPose();
                if (pose != null) poses.put(sub.getUniqueId(), pose);
            }
        }
        float max = 0f;
        Vector3d corner = new Vector3d();
        Vector3d world = new Vector3d();
        Vector3f dir = new Vector3f();
        for (Hidden h : blocks) {
            Pose3dc pose = h.subLevel() == null ? null : poses.get(h.subLevel());
            if (h.subLevel() != null && pose == null) continue;
            int bx = BlockPos.getX(h.pos()), by = BlockPos.getY(h.pos()), bz = BlockPos.getZ(h.pos());
            for (int c = 0; c < 8; c++) {
                corner.set(bx + (c & 1), by + ((c >> 1) & 1), bz + ((c >> 2) & 1));
                if (pose != null) pose.transformPosition(corner, world);
                else world.set(corner);
                dir.set((float) (world.x - eye.x), (float) (world.y - eye.y), (float) (world.z - eye.z));
                if (dir.lengthSquared() < 1.0e-6f) return MAX_REACH;
                float[] hit = ConstellationGeometry.hit(dir.normalize());
                float r = hit == null ? MAX_REACH : (float) Math.sqrt(hit[0] * hit[0] + hit[1] * hit[1]);
                if (r > max) max = r;
                if (max >= MAX_REACH) return MAX_REACH;
            }
        }
        return max;
    }

    private static void carriages(ClientLevel level, Vec3 eye, List<Vector3f> directions, Set<Hidden> out,
                                  BlockPos.MutableBlockPos cursor) {
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        AABB near = new AABB(eye, eye).inflate(REACH);
        for (ClientSubLevel sub : container.getAllSubLevels()) {
            BoundingBox3dc box = sub.boundingBox();
            if (box == null || !box.intersects(near)) continue;
            Pose3dc pose = sub.logicalPose();
            LevelPlot plot = sub.getPlot();
            if (pose == null || plot == null) continue;
            Map<Long, LevelChunk> chunks = chunksOf(plot);
            Vector3d a = pose.transformPositionInverse(new Vector3d(eye.x, eye.y, eye.z));
            UUID id = sub.getUniqueId();
            for (Vector3f d : directions) {
                Vector3d b = pose.transformPositionInverse(
                    new Vector3d(eye.x + d.x * REACH, eye.y + d.y * REACH, eye.z + d.z * REACH));
                walk(new Vec3(a.x, a.y, a.z), new Vec3(b.x, b.y, b.z), pos -> {
                    LevelChunk chunk = chunks.get(ChunkPos.asLong(pos[0] >> 4, pos[2] >> 4));
                    if (chunk == null) return;
                    cursor.set(pos[0], pos[1], pos[2]);
                    if (blocks(chunk.getBlockState(cursor), chunk, cursor)) out.add(new Hidden(id, cursor.asLong()));
                });
            }
        }
    }

    /** Anything with a shape counts: solid blocks, glass, leaves, slabs. Not air, fluids or the patch's own blocks. */
    private static boolean blocks(BlockState state, net.minecraft.world.level.BlockGetter getter, BlockPos pos) {
        if (state.isAir() || games.brennan.dungeontrain.block.ConstellationBlocks.isLive(state)) return false;
        return !state.getShape(getter, pos).isEmpty();
    }

    private static Map<Long, LevelChunk> chunksOf(LevelPlot plot) {
        Map<Long, LevelChunk> chunks = new HashMap<>();
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk != null) chunks.put(chunk.getPos().toLong(), chunk);
        }
        return chunks;
    }

    /**
     * Every block cell the segment {@code from -> to} passes through, in order (Amanatides and Woo's
     * voxel walk). The visitor gets {@code {x, y, z}}; the array is reused.
     */
    static void walk(Vec3 from, Vec3 to, java.util.function.Consumer<int[]> visit) {
        int[] cell = {floor(from.x), floor(from.y), floor(from.z)};
        int[] last = {floor(to.x), floor(to.y), floor(to.z)};
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        int sx = (int) Math.signum(dx), sy = (int) Math.signum(dy), sz = (int) Math.signum(dz);
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);
        double tmx = dx == 0 ? Double.MAX_VALUE : tdx * (sx > 0 ? cell[0] + 1 - from.x : from.x - cell[0]);
        double tmy = dy == 0 ? Double.MAX_VALUE : tdy * (sy > 0 ? cell[1] + 1 - from.y : from.y - cell[1]);
        double tmz = dz == 0 ? Double.MAX_VALUE : tdz * (sz > 0 ? cell[2] + 1 - from.z : from.z - cell[2]);
        int guard = 4 * (int) Math.ceil(Math.abs(dx) + Math.abs(dy) + Math.abs(dz)) + 4;
        while (guard-- > 0) {
            visit.accept(cell);
            if (cell[0] == last[0] && cell[1] == last[1] && cell[2] == last[2]) return;
            if (tmx < tmy && tmx < tmz) {
                if (tmx > 1) return;
                cell[0] += sx;
                tmx += tdx;
            } else if (tmy < tmz) {
                if (tmy > 1) return;
                cell[1] += sy;
                tmy += tdy;
            } else {
                if (tmz > 1) return;
                cell[2] += sz;
                tmz += tdz;
            }
        }
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    /** Unit tests: the walk as a list. */
    static List<int[]> cells(Vec3 from, Vec3 to) {
        List<int[]> out = new ArrayList<>();
        walk(from, to, c -> out.add(c.clone()));
        return out;
    }
}
