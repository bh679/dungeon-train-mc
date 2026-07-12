package games.brennan.dungeontrain.client.snapshot;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Render-time line-of-sight test against <em>Sable carriage walls</em>.
 *
 * <p>Carriage blocks live in each carriage's Sable sub-level (its {@code LevelPlot}),
 * <b>not</b> in the main client level, so {@link ClientLevel#clip} cannot see them — a
 * camera placed just outside a walled carriage reads "clear" while the wall actually
 * hides the player. This class casts the camera→player segment through every nearby
 * carriage's blocks so {@link SnapshotCamera} can reject any angle that a wall occludes.</p>
 *
 * <p>It mirrors the established sub-level scan idioms in the codebase:
 * {@link games.brennan.dungeontrain.client.snapshot.NearestCarriage} /
 * {@link games.brennan.dungeontrain.client.sound.TrainEngineSound} for discovery
 * ({@link SubLevelContainer#getContainer(ClientLevel)} →
 * {@link ClientSubLevelContainer#getAllSubLevels()}),
 * {@link games.brennan.dungeontrain.client.CarriageGroupGapDebugRenderer} for the
 * {@link ClientSubLevel#renderPose(float) renderPose} transform, and
 * {@link games.brennan.dungeontrain.event.VillagerJobSiteAssigner} for the plot
 * chunk-map block reads — only here it runs client-side on the interpolated render pose.</p>
 *
 * <p><b>Must be used at render time</b> with world-space coordinates: a player riding a
 * Sable ship reports far sub-level coords during the tick (see the
 * {@code project_sable_tick_vs_render_coords} memory note), and the {@code renderPose}
 * is the transform Sable actually draws the carriage blocks with this frame.</p>
 */
public final class CarriageOcclusion {

    private CarriageOcclusion() {}

    /**
     * One loaded carriage captured for the current frame: its interpolated render
     * transform ({@code renderPose}), a chunk map of its plot keyed by
     * {@code ChunkPos#toLong()}, and its world-space AABB.
     */
    public record Carriage(Pose3dc pose, Map<Long, LevelChunk> chunks, BoundingBox3dc box) {}

    /**
     * Snapshot every loaded carriage within {@code radius} of {@code player} for this
     * frame, so a run of candidate-angle tests can reuse one capture (the render pose +
     * chunk map are gathered once, not per candidate).
     *
     * <p>Carriages with no AABB, a not-yet-ticked zero AABB, or no loaded plot chunks are
     * skipped, as is any carriage whose AABB doesn't reach the player's neighbourhood (a
     * wall on a short camera→player segment must belong to a carriage near the player).</p>
     */
    public static List<Carriage> gatherNearby(ClientLevel level, Vec3 player, double radius, float partialTick) {
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return List.of();

        AABB near = new AABB(player, player).inflate(radius);
        List<Carriage> out = new ArrayList<>();
        for (ClientSubLevel sub : container.getAllSubLevels()) {
            BoundingBox3dc box = sub.boundingBox();
            if (box == null) continue;
            // Fresh sub-levels report a zero AABB before their first physics tick — skip
            // (same defensive check NearestCarriage / TrainEngineSound use).
            if (box.minX() == 0 && box.minY() == 0 && box.minZ() == 0
                && box.maxX() == 0 && box.maxY() == 0 && box.maxZ() == 0) continue;
            if (!box.intersects(near)) continue;

            Pose3dc pose = sub.renderPose(partialTick);
            if (pose == null) continue;
            LevelPlot plot = sub.getPlot();
            if (plot == null) continue;

            Map<Long, LevelChunk> chunks = new HashMap<>();
            for (PlotChunkHolder holder : plot.getLoadedChunks()) {
                LevelChunk chunk = holder.getChunk();
                if (chunk != null) {
                    chunks.put(chunk.getPos().toLong(), chunk);
                }
            }
            if (chunks.isEmpty()) continue;
            out.add(new Carriage(pose, chunks, box));
        }
        return out;
    }

    /**
     * Is the straight line from the camera to the player blocked by any carriage wall?
     *
     * <p>Two body heights are tested (e.g. chest and head); a hit on <b>either</b> counts
     * as occluded, so a half-wall that hides only part of the player still fails — we err
     * toward skipping rather than ever framing a hidden player.</p>
     */
    public static boolean blocked(List<Carriage> carriages, Vec3 camWorld, Vec3 bodyLow, Vec3 bodyHigh) {
        for (Carriage carriage : carriages) {
            if (segmentHitsCarriage(carriage, camWorld, bodyLow)
                || segmentHitsCarriage(carriage, camWorld, bodyHigh)) {
                return true;
            }
        }
        return false;
    }

    /** True if a solid carriage block lies on the world segment {@code camWorld → bodyWorld}. */
    private static boolean segmentHitsCarriage(Carriage carriage, Vec3 camWorld, Vec3 bodyWorld) {
        // World → ship-local (plot storage) space: the inverse of the renderPose that draws
        // the blocks, so the ray is tested in the exact frame getBlockState indexes them in.
        Vec3 from = carriage.pose().transformPositionInverse(camWorld);
        Vec3 to = carriage.pose().transformPositionInverse(bodyWorld);
        if (from.distanceToSqr(to) < 1.0e-6) return false;

        Map<Long, LevelChunk> chunks = carriage.chunks();
        Boolean hit = BlockGetter.<Boolean, Map<Long, LevelChunk>>traverseBlocks(
                from, to, chunks,
                (map, pos) -> occludes(map, pos) ? Boolean.TRUE : null,
                map -> Boolean.FALSE);
        return Boolean.TRUE.equals(hit);
    }

    /** A solid, view-blocking block at {@code pos} in the plot's storage space. */
    private static boolean occludes(Map<Long, LevelChunk> chunks, BlockPos pos) {
        LevelChunk chunk = chunks.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (chunk == null) return false; // unbuilt region of the plot — open air, see-through
        BlockState state = chunk.getBlockState(pos);
        if (state.isAir()) return false;
        // Full opaque cubes (plank/stone walls) OR any collision block (slabs, fences, glass):
        // both hide the player, and the safe direction is to treat a borderline block as a wall.
        return state.isSolidRender(chunk, pos) || !state.getCollisionShape(chunk, pos).isEmpty();
    }
}
