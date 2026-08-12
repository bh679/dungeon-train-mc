package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Redraws the Train Builder's corridor track see-through while a track template is open.
 *
 * <p>The other half of {@code ModelBlockRendererTrackGhostMixin}, which takes these blocks out of
 * the chunk mesh. Hidden and redrawn here at half alpha, they become context rather than content:
 * the open plot is the only solid track in the corridor, and the line either side is still there to
 * judge the seam against.</p>
 *
 * <p>Drawn from the block's <b>real model</b> via {@link BlockRenderDispatcher#renderSingleBlock},
 * not from unit cubes. The corridor's two rows are a bed of full blocks and a row of rails, and a
 * rail is not a cube — boxing it would produce a floating slab where a builder expects a rail. This
 * is the thing a quad overlay like {@link OutOfBoundsWashRenderer} cannot do, and the reason that
 * renderer's approach was not simply reused.</p>
 *
 * <p>Cadence copied from that renderer for the same reason: sweeping the corridor every frame would
 * be waste, so the sweep runs on a {@value #RESCAN_INTERVAL_TICKS}-tick tick and the render pass
 * walks a cached list.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BuilderTrackGhostRenderer {

    private static final int RESCAN_INTERVAL_TICKS = 10;
    /** How far along the line to ghost. Past this the track is too small to read anyway. */
    private static final int SCAN_RADIUS_X = 48;
    /** Hard ceiling so a pathological world can't stall a frame. */
    private static final int MAX_BLOCKS = 4_096;

    /** Half opacity — present enough to line the plot up against, faint enough to never be mistaken for it. */
    private static final float GHOST_ALPHA = 0.5F;

    /**
     * Culled and depth-writing, so only the surface facing the camera is drawn and nothing ghosted
     * renders behind anything else ghosted. See {@link BuilderRenderStates#ghostBlock}.
     */
    private static final RenderType GHOST = BuilderRenderStates.ghostBlock(
            DungeonTrain.MOD_ID + ":builder_track_ghost");

    /** Cached ghost positions, replaced wholesale so a render pass never sees a partial sweep. */
    private static volatile List<BlockPos> ghosts = List.of();
    private static int tickCounter = 0;

    private BuilderTrackGhostRenderer() {}

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        if (++tickCounter < RESCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        rescan();
    }

    /**
     * Collect the corridor blocks to ghost, around the player.
     *
     * <p>Walks the two track rows across the corridor's width rather than a cube around the player:
     * the set is defined by {@link ClientTrackGhost#isGhosted}, and everything outside those rows
     * would be rejected by it anyway.</p>
     */
    private static void rescan() {
        if (!ClientTrackGhost.isActive()) {
            ghosts = List.of();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            ghosts = List.of();
            return;
        }
        BoundingBox plot = ClientTrackGhost.plot();
        if (plot == null) {
            ghosts = List.of();
            return;
        }

        // Centred on the plot, not the player: the plot is what you are looking at, and centring on
        // the player would drop the far end of the ghosted run the moment you stepped back to see it.
        int centreX = (plot.minX() + plot.maxX()) / 2;
        List<BlockPos> found = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // The two track rows across the corridor's width — the only blocks isGhosted can say yes to,
        // so the sweep walks exactly that box rather than a cube around the player.
        int corridorWidth = CarriageDims.DEFAULT.width();

        outer:
        for (int x = centreX - SCAN_RADIUS_X; x <= centreX + SCAN_RADIUS_X; x++) {
            for (int y = BuilderWorldLayout.Y_TRACK_BED; y <= BuilderWorldLayout.Y_TRACK_RAIL; y++) {
                for (int z = 0; z < corridorWidth; z++) {
                    pos.set(x, y, z);
                    if (!ClientTrackGhost.isGhosted(pos) || mc.level.getBlockState(pos).isAir()) {
                        continue;
                    }
                    found.add(pos.immutable());
                    if (found.size() >= MAX_BLOCKS) {
                        break outer;
                    }
                }
            }
        }
        ghosts = found;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        List<BlockPos> snapshot = ghosts;
        if (snapshot.isEmpty() || !ClientTrackGhost.isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }

        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        BlockRenderDispatcher blocks = mc.getBlockRenderer();
        // Every quad the model emits is forced into GHOST — the type is ignored deliberately, since
        // a model that asked for the cutout or solid layer would otherwise escape the depth-write
        // rule that stops ghosts stacking. Wrapping the buffer rather than tinting afterwards is
        // what lets the model keep its own texture and shape: the ghost is the block, faded, not a
        // coloured stand-in for it.
        MultiBufferSource ghosted = type -> new GhostVertexConsumer(buffer.getBuffer(GHOST));

        // Front-to-back. GHOST writes depth, so whichever fragment lands first wins the pixel and
        // everything behind it is dropped — which is only the answer you want if the nearest one
        // got there first. Sorted per frame because the ordering is the camera's, not the world's.
        List<BlockPos> ordered = new ArrayList<>(snapshot);
        ordered.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(cam)));

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        for (BlockPos pos : ordered) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;   // the sweep is up to ten ticks old; the builder may have dug it out
            }
            ps.pushPose();
            ps.translate(pos.getX(), pos.getY(), pos.getZ());
            blocks.renderSingleBlock(state, ps, ghosted, LevelRenderer.getLightColor(level, pos),
                    OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.translucent());
            ps.popPose();
        }
        ps.popPose();
        buffer.endBatch(GHOST);
    }

    /**
     * A {@link VertexConsumer} that fades everything drawn through it.
     *
     * <p>Delegates every call untouched except the colour, whose alpha it scales. Every other
     * attribute — texture, light, normal, position — is the model's own, which is the point.</p>
     */
    private record GhostVertexConsumer(VertexConsumer delegate) implements VertexConsumer {

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, (int) (alpha * GHOST_ALPHA));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }
}
