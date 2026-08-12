package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderTrackGhostShape;
import games.brennan.dungeontrain.builder.BuilderTrackPlot;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Draws what surrounds an open track template — the rest of the line, and the rest of a pillar
 * column — as translucent ghosts.
 *
 * <p>A track tile is four blocks of a line that runs three hundred, and a pillar section is a slab
 * whose whole job is to hold a line up. Neither tells you much on its own; what you are actually
 * judging is the join. So the surroundings are drawn back in, and the plot is left as the only solid
 * thing in the corridor.</p>
 *
 * <p><b>Drawn, never stamped</b> — the corridor is erased on the way into a track build
 * ({@code BuilderWorldSetup.applyOpenTrack}) and everything you see around the plot is these quads.
 * A ghost cannot be broken, walked on, or saved into the template, because there is nothing there.
 * The same choice {@code BuilderGhostTrainRenderer} makes for the rest of the carriage group, and it
 * is what let the chunk-meshing mixin this class used to need go away.</p>
 *
 * <p>Two sources of geometry, for one reason. A tile or a tunnel is authored <em>in</em> the
 * corridor, so the real thing is on the plot and the line either side is that plot repeated — the
 * template tessellating, which is exactly what you want to judge a seam by. A pillar or a staircase
 * is authored beside the corridor with no line near it, so the line and the rest of the column are
 * modelled by {@link BuilderTrackGhostShape}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BuilderTrackGhostRenderer {

    private static final RenderType GHOST_QUAD = BuilderGhostQuads.renderType("builder_track_ghost");

    private static final int RESCAN_INTERVAL_TICKS = 10;
    /** How many plot-lengths of repeat to draw either side, for the corridor kinds. */
    private static final int REPEATS_EACH_WAY = 12;
    /** Hard ceiling so a pathological build can't stall a frame. Matches the wash's. */
    private static final int MAX_FACES = 16_384;

    /** Cached exposed faces, replaced wholesale so a render pass never sees a partial sweep. */
    private static volatile List<Face> faces = List.of();
    private static int tickCounter = 0;

    private record Face(BlockPos pos, Direction dir) {}

    private BuilderTrackGhostRenderer() {}

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        if (++tickCounter < RESCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        rescan();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        faces = List.of();
    }

    private static void rescan() {
        Minecraft mc = Minecraft.getInstance();
        TrackKind kind = ClientTrackGhost.kind();
        BoundingBox plot = ClientTrackGhost.plot();
        if (kind == null || plot == null || mc.level == null) {
            faces = List.of();
            return;
        }
        Set<BlockPos> cells = BuilderTrackPlot.inCorridor(kind)
                ? repeatedPlotCells(mc.level, plot)
                : new HashSet<>(BuilderTrackGhostShape.cells(kind, plot, CarriageDims.DEFAULT));
        faces = exposedFaces(cells);
    }

    /**
     * The plot's own solid blocks, repeated along X at plot-length intervals.
     *
     * <p>The template tessellating down the line — the real one, read out of the world rather than
     * modelled, because for these kinds it is right there on the plot. The same trick the carriage
     * ghosts use to fill empty slots from the one parked carriage.</p>
     */
    private static Set<BlockPos> repeatedPlotCells(Level level, BoundingBox plot) {
        List<BlockPos> local = new ArrayList<>();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int x = plot.minX(); x <= plot.maxX(); x++) {
            for (int y = plot.minY(); y <= plot.maxY(); y++) {
                for (int z = plot.minZ(); z <= plot.maxZ(); z++) {
                    if (!level.getBlockState(probe.set(x, y, z)).isAir()) {
                        local.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        Set<BlockPos> cells = new HashSet<>();
        int step = plot.getXSpan();
        for (int repeat = -REPEATS_EACH_WAY; repeat <= REPEATS_EACH_WAY; repeat++) {
            if (repeat == 0) {
                continue;   // the plot itself is real; ghosting it would double it
            }
            int offset = repeat * step;
            for (BlockPos pos : local) {
                cells.add(pos.offset(offset, 0, 0));
            }
        }
        return cells;
    }

    /**
     * Faces of the ghost mass that aren't buried inside it.
     *
     * <p>An interior face is invisible in a solid and, at this alpha, a second translucent layer of
     * it reads as a darker stripe rather than as depth — so a run contributes its outer shell.</p>
     */
    private static List<Face> exposedFaces(Set<BlockPos> cells) {
        List<Face> found = new ArrayList<>();
        for (BlockPos pos : cells) {
            for (Direction dir : Direction.values()) {
                if (cells.contains(pos.relative(dir))) {
                    continue;
                }
                found.add(new Face(pos, dir));
                if (found.size() >= MAX_FACES) {
                    return found;
                }
            }
        }
        return found;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        List<Face> snapshot = faces;
        if (snapshot.isEmpty() || !ClientTrackGhost.isActive()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffer.getBuffer(GHOST_QUAD);

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        for (Face face : snapshot) {
            BlockPos p = face.pos();
            BuilderGhostQuads.drawFace(ps, vc, p.getX(), p.getY(), p.getZ(), face.dir());
        }
        ps.popPose();
        buffer.endBatch(GHOST_QUAD);
    }
}
