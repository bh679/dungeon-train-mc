package games.brennan.dungeontrain.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import games.brennan.dungeontrain.block.PhotographFrameBlock;
import games.brennan.dungeontrain.block.entity.PhotographFrameBlockEntity;
import games.brennan.dungeontrain.compat.photo.PhotoFrameLayout;
import io.github.mortuusars.exposure.Config;
import io.github.mortuusars.exposure.ExposureClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Draws a block photo frame the way Exposure's {@code PhotographFrameEntityRenderer} draws the
 * entity: Exposure's own baked frame model and its photograph renderer, with the same offsets,
 * rotation, glow and brightness. Placed on the master cell; the pose is moved to the frame's centre
 * first, which is where the entity renderer's origin sits.
 *
 * <p>Exposure's "pixel-perfect" frame option is not mirrored — photos always draw through
 * {@code photographRenderer()}, Exposure's default.</p>
 */
public class PhotographFrameBlockRenderer implements BlockEntityRenderer<PhotographFrameBlockEntity> {

    /** Exposure's {@code PhotographFrameEntityRenderer} constants. */
    private static final float FRAME_BORDER = 0.125F;
    private static final float PHOTO_Z = 0.48F;
    private static final float PHOTO_Z_NO_FRAME = 0.497F;
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    private final BlockRenderDispatcher blockRenderer;

    public PhotographFrameBlockRenderer(BlockEntityRendererProvider.Context context) {
        this.blockRenderer = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(PhotographFrameBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof PhotographFrameBlock)) return;
        Direction facing = state.getValue(PhotographFrameBlock.FACING);
        boolean glass = state.getValue(PhotographFrameBlock.GLASS);
        int size = be.size();
        int width = PhotoFrameLayout.width(size);
        boolean frameHidden = glass && be.hasPhoto();

        pose.pushPose();
        float half = (width - 1) / 2.0F;
        Direction ccw = facing.getCounterClockWise();
        pose.translate(0.5F + ccw.getStepX() * half, 0.5F - half, 0.5F + ccw.getStepZ() * half);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - facing.get2DDataValue() * 90.0F));

        if (be.hasPhoto()) renderPhoto(be, facing, width, frameHidden, pose, buffers, light);
        if (!frameHidden) renderFrame(glass, size, pose, buffers, light);
        pose.popPose();
    }

    private void renderPhoto(PhotographFrameBlockEntity be, Direction facing, int width, boolean frameHidden,
                             PoseStack pose, MultiBufferSource buffers, int light) {
        float border = frameHidden ? 0.0F : FRAME_BORDER;
        float z = (frameHidden ? PHOTO_Z_NO_FRAME : PHOTO_Z) - imageOffset();
        float scale = width - 2 * border;

        pose.pushPose();
        pose.mulPose(Axis.ZP.rotationDegrees(be.itemRotation() * 90.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(180.0F));
        pose.translate(-0.5F * width + border, -0.5F * width + border, z);
        pose.scale(scale, scale, 1.0F);
        int photoLight = be.glowing() ? FULL_BRIGHT : light;
        int b = be.glowing() ? 255 : brightness(be.getLevel(), be.getBlockPos(), facing);
        ExposureClient.photographRenderer().render(be.photo(), false, false, pose, buffers, photoLight, b, b, b, 255);
        pose.popPose();
    }

    private void renderFrame(boolean glass, int size, PoseStack pose, MultiBufferSource buffers, int light) {
        BakedModel model = Minecraft.getInstance().getModelManager().getModel(frameModel(glass, size));
        pose.pushPose();
        pose.translate(-0.5F, -0.5F, -0.5F);
        blockRenderer.getModelRenderer().renderModel(pose.last(),
            buffers.getBuffer(glass ? Sheets.cutoutBlockSheet() : Sheets.solidBlockSheet()),
            null, model, 1.0F, 1.0F, 1.0F, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    private static ModelResourceLocation frameModel(boolean glass, int size) {
        return switch (PhotoFrameLayout.clampSize(size)) {
            case 0 -> glass ? ExposureClient.Models.CLEAR_PHOTOGRAPH_FRAME_SMALL : ExposureClient.Models.PHOTOGRAPH_FRAME_SMALL;
            case 1 -> glass ? ExposureClient.Models.CLEAR_PHOTOGRAPH_FRAME_MEDIUM : ExposureClient.Models.PHOTOGRAPH_FRAME_MEDIUM;
            default -> glass ? ExposureClient.Models.CLEAR_PHOTOGRAPH_FRAME_LARGE : ExposureClient.Models.PHOTOGRAPH_FRAME_LARGE;
        };
    }

    /** Exposure's {@code getPhotographBrightness}: face shade, softened, lifted by block light. */
    private static int brightness(Level level, BlockPos pos, Direction facing) {
        if (level == null) return 255;
        float shade = level.getShade(facing, true);
        int base = (int) ((shade + (1.0F - shade) * 0.2F) * 255.0F);
        int blockLight = level.getBrightness(LightLayer.BLOCK, pos);
        return Math.min(255, base + (int) ((255 - base) * (blockLight / 15.0F * 0.5F)));
    }

    /** Exposure's client "photo offset" option; 0 before its config has loaded. */
    private static float imageOffset() {
        try {
            return Config.Client.PHOTOGRAPH_FRAME_IMAGE_OFFSET.get().floatValue();
        } catch (IllegalStateException e) {
            return 0.0F;
        }
    }

    /** Covers a 3×3 frame on any side of the master so it is not culled when the master cell is off screen. */
    @Override
    public AABB getRenderBoundingBox(PhotographFrameBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(PhotoFrameLayout.MAX_SIZE + 1);
    }
}
