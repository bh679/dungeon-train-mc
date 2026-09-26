package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.vertex.PoseStack;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.worldgen.VoidWallPlane;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.List;

/**
 * The track past the void wall, in the vanilla world. The wall hides every chunk section reaching past
 * it — the ones the track runs through included, because keeping those kept the End islands sitting in
 * them too — so the track's own blocks are drawn here instead, one by one, straight from the client
 * level (they are loaded; only their sections are culled). The rails therefore look exactly as they
 * do on the near side and carry on up to the vanilla render distance; past that, Distant Horizons'
 * boxes take over ({@code DistantHorizonsVoidWall}).
 *
 * <p>Only the track corridor's two rows — the bed and the rails — are drawn, and only from the first
 * hidden section to the render distance. That stretch is empty whenever the wall is further away than
 * the render distance, which is most of the approach to a void.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class VoidWallTrackRenderer {

    /** Frames between re-reading the corridor's blocks from the level. */
    private static final int REFRESH_FRAMES = 10;

    private record TrackBlock(BlockPos pos, BlockState state) {}

    /** Render thread only. */
    private static List<TrackBlock> blocks = List.of();
    private static int framesSinceRefresh = REFRESH_FRAMES;

    private VoidWallTrackRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) return;
        VoidWallPlane wall = ClientVoidWall.plane();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (!wall.active() || level == null) {
            blocks = List.of();
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        if (++framesSinceRefresh >= REFRESH_FRAMES) {
            framesSinceRefresh = 0;
            blocks = collect(level, wall, cam.x + mc.options.getEffectiveRenderDistance() * 16.0);
        }
        if (blocks.isEmpty()) return;

        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        PoseStack ps = event.getPoseStack();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        for (TrackBlock b : blocks) {
            ps.pushPose();
            ps.translate(b.pos().getX(), b.pos().getY(), b.pos().getZ());
            dispatcher.renderSingleBlock(b.state(), ps, buffer,
                    LevelRenderer.getLightColor(level, b.pos()), OverlayTexture.NO_OVERLAY,
                    ModelData.EMPTY, RenderType.cutout());
            ps.popPose();
        }
        ps.popPose();
        buffer.endBatch(RenderType.cutout());
    }

    /** The corridor's non-air bed and rail blocks from the first hidden section up to {@code toX}. */
    private static List<TrackBlock> collect(ClientLevel level, VoidWallPlane wall, double toX) {
        int from = Math.floorDiv((int) Math.floor(wall.wallX()), 16) * 16;
        int to = (int) Math.ceil(toX);
        if (from >= to) return List.of();
        int railY = ClientUpsideDownBand.trainY() - 1;
        List<TrackBlock> out = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = from; x < to; x++) {
            for (int y = railY - 1; y <= railY; y++) {
                for (int z = 0; z < CarriageDims.MAX_WIDTH; z++) {
                    p.set(x, y, z);
                    if (!level.isLoaded(p)) continue;
                    BlockState state = level.getBlockState(p);
                    if (state.isAir()) continue;
                    out.add(new TrackBlock(p.immutable(), state));
                }
            }
        }
        return List.copyOf(out);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        blocks = List.of();
        framesSinceRefresh = REFRESH_FRAMES;
    }
}
