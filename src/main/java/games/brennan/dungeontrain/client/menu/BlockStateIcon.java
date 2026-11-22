package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A menu icon drawn from a {@link BlockState}'s own block model rather than from its item, so a
 * turned block (stairs facing east, a log lying on its side) shows that facing. An item icon is
 * always the block's default orientation.
 *
 * <p>The transform is vanilla's {@code block/block} GUI display (rotate 30, 225; scale 0.625),
 * so a turned icon sits in the same three-quarter view, at the same size, as the item icons
 * around it. Only blocks drawn by a plain model qualify ({@link #canDraw}); chests, banners,
 * signs and heads are drawn by block-entity renderers and keep their item icon.</p>
 */
public final class BlockStateIcon {

    private static final float GUI_PITCH = 30.0f;
    private static final float GUI_YAW = 225.0f;
    private static final float GUI_SCALE = 0.625f;
    /** {@code GuiGraphics#renderItem}'s depth lift, so the model clears the panel fill. */
    private static final float GUI_Z = 150.0f;

    private BlockStateIcon() {}

    /** True when {@code state} has a block model this icon can draw. */
    public static boolean canDraw(BlockState state) {
        return state != null && !state.isAir() && state.getRenderShape() == RenderShape.MODEL;
    }

    /** World-space panels: draw centred on the pose's origin, {@code size} units across. */
    public static void drawWorld(PoseStack ps, MultiBufferSource buffer, BlockState state,
                                 double centerX, double centerY, double size) {
        ps.pushPose();
        ps.translate(centerX, centerY, 0.002);
        ps.scale((float) size, (float) size, (float) size);
        drawModel(ps, buffer, state);
        ps.popPose();
    }

    /** Screen-space panels: draw centred on {@code (x, y)} pixels, {@code sizePx} across. */
    public static void drawGui(GuiGraphics gg, BlockState state, float x, float y, float sizePx) {
        PoseStack ps = gg.pose();
        ps.pushPose();
        ps.translate(x, y, GUI_Z);
        // Screen y runs down; models are built y-up.
        ps.scale(sizePx, -sizePx, sizePx);
        Lighting.setupFor3DItems();
        drawModel(ps, gg.bufferSource(), state);
        gg.flush();
        ps.popPose();
    }

    private static void drawModel(PoseStack ps, MultiBufferSource buffer, BlockState state) {
        ps.mulPose(Axis.XP.rotationDegrees(GUI_PITCH));
        ps.mulPose(Axis.YP.rotationDegrees(GUI_YAW));
        ps.scale(GUI_SCALE, GUI_SCALE, GUI_SCALE);
        ps.translate(-0.5, -0.5, -0.5);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
            state, ps, buffer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
    }
}
