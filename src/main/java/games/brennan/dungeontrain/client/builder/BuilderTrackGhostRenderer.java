package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
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
import java.util.List;

/**
 * Draws the rest of the line as translucent ghosts around the track template being edited.
 *
 * <p>A track tile is a four-block slice of a corridor that runs three hundred blocks in both
 * directions, out of the same blocks, at the same height. Stamped, it is invisible — there is
 * nothing to tell the template from the scenery, and editing the wrong four blocks looks exactly
 * like editing the right ones. Ghosting the rest makes the plot the only solid track in the
 * corridor, while leaving the line there to judge the seam against.</p>
 *
 * <p>The other half of {@code ModelBlockRendererTrackGhostMixin}. Unlike the carriage-group
 * ghosts, which stand in for carriages that <em>aren't there</em>, these blocks
 * are real — so drawing over them would only tint them. The mixin drops them from the chunk mesh and
 * this draws them back, which is also what lets the grass beneath show through where the track used
 * to hide it.</p>
 *
 * <p>The look comes from {@link BuilderGhostQuads}, shared with the carriage ghosts: the same pale
 * translucent face quads, because they mean the same thing. Only faces onto air are emitted, so a
 * run of adjacent track contributes its outer shell rather than a stack of internal faces that would
 * compound into a solid-looking wall.</p>
 *
 * <p>Cadence copied from {@link OutOfBoundsWashRenderer}: sweeping the corridor every frame would be
 * waste, so the sweep runs on a {@value #RESCAN_INTERVAL_TICKS}-tick cadence and the render pass
 * walks a cached list.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BuilderTrackGhostRenderer {

    private static final RenderType GHOST_QUAD = BuilderGhostQuads.renderType("builder_track_ghost");

    private static final int RESCAN_INTERVAL_TICKS = 10;
    /** How far along the line to ghost. Past this the track is too small to read anyway. */
    private static final int SCAN_RADIUS_X = 48;
    /** Hard ceiling so a pathological world can't stall a frame. Matches the wash's. */
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

    /**
     * Collect the corridor faces to ghost.
     *
     * <p>Walks the two track rows across the corridor's width rather than a cube around the player:
     * those are the only blocks {@link ClientTrackGhost#isGhosted} can say yes to, so anything else
     * would be swept only to be rejected.</p>
     */
    private static void rescan() {
        Minecraft mc = Minecraft.getInstance();
        BoundingBox plot = ClientTrackGhost.plot();
        if (!ClientTrackGhost.isActive() || plot == null || mc.level == null) {
            faces = List.of();
            return;
        }
        Level level = mc.level;

        // Centred on the plot, not the player: the plot is what you are looking at, and centring on
        // the player would drop the far end of the ghosted run the moment you stepped back to see it.
        int centreX = (plot.minX() + plot.maxX()) / 2;
        int corridorWidth = CarriageDims.DEFAULT.width();

        List<Face> found = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();

        outer:
        for (int x = centreX - SCAN_RADIUS_X; x <= centreX + SCAN_RADIUS_X; x++) {
            for (int y = BuilderWorldLayout.Y_TRACK_BED; y <= BuilderWorldLayout.Y_TRACK_RAIL; y++) {
                for (int z = 0; z < corridorWidth; z++) {
                    pos.set(x, y, z);
                    if (!ClientTrackGhost.isGhosted(pos) || level.getBlockState(pos).isAir()) {
                        continue;
                    }
                    for (Direction dir : Direction.values()) {
                        neighbour.set(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
                        // Only faces onto air. An interior face is invisible in a solid mass, and a
                        // second translucent layer of it reads as a darker stripe rather than depth.
                        // Neighbouring ghosts count as solid here: they are still real blocks, just
                        // ones the mixin stopped drawing.
                        if (!level.getBlockState(neighbour).isAir()) {
                            continue;
                        }
                        found.add(new Face(pos.immutable(), dir));
                        if (found.size() >= MAX_FACES) {
                            break outer;
                        }
                    }
                }
            }
        }
        faces = found;
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
