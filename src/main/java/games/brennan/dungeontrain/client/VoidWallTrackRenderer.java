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
 * The track past the void walls, in the vanilla world — ahead of the wall in front and behind the wall
 * at the back. A wall hides every chunk section reaching past it — the track's own included, because
 * keeping those kept the End islands sitting in them too — so the track's blocks are drawn here instead,
 * one by one, straight from the client level (they are loaded; only their sections are culled). The
 * rails therefore look exactly as they do on the near side and carry on out to the vanilla render
 * distance; past that, Distant Horizons' boxes take over ({@code DistantHorizonsVoidWall}).
 *
 * <p>Only the track corridor's two rows — the bed and the rails — are drawn, and only in the hidden
 * sections within the render distance. That is nothing whenever both walls are further away than the
 * render distance, which is most of the time.</p>
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
            blocks = collect(level, wall, cam.x, mc.options.getEffectiveRenderDistance() * 16.0);
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

    /**
     * The corridor's non-air bed and rail blocks in the hidden sections within {@code reach} of the
     * camera: from the first section reaching past the wall ahead, and up to the last one reaching back
     * past the wall behind.
     */
    private static List<TrackBlock> collect(ClientLevel level, VoidWallPlane wall, double camX, double reach) {
        List<TrackBlock> out = new ArrayList<>();
        if (!Double.isInfinite(wall.wallX())) {
            int from = Math.floorDiv((int) Math.floor(wall.wallX()), 16) * 16;
            collectRange(level, from, (int) Math.ceil(camX + reach), out);
        }
        if (!Double.isInfinite(wall.backX())) {
            int to = (Math.floorDiv((int) Math.floor(wall.backX()), 16) + 1) * 16;
            collectRange(level, (int) Math.floor(camX - reach), to, out);
        }
        return List.copyOf(out);
    }

    private static void collectRange(ClientLevel level, int from, int to, List<TrackBlock> out) {
        if (from >= to) return;
        int railY = ClientUpsideDownBand.trainY() - 1;
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
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        blocks = List.of();
        framesSinceRefresh = REFRESH_FRAMES;
    }
}
