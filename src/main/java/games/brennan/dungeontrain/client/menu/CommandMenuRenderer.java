package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.List;

/**
 * Renders the worldspace command menu during the translucent pass of
 * the level render. The panel is a flat rectangle positioned at the
 * anchor; its local X/Y/Z axes are {@link CommandMenuState#anchorRight()},
 * {@link CommandMenuState#anchorUp()}, {@link CommandMenuState#anchorNormal()}
 * (unit vectors captured at open time).
 *
 * <p>Row types and their rendering:
 * <ul>
 *   <li>{@link CommandMenuEntry.Run} / {@link CommandMenuEntry.DrillIn} /
 *       {@link CommandMenuEntry.Back} / {@link CommandMenuEntry.TypeArg} /
 *       {@link CommandMenuEntry.Loading} — full-width button row.</li>
 *   <li>{@link CommandMenuEntry.Toggle} — full-width with the state
 *       appended as {@code [ON]}/{@code [OFF]}; the backdrop tints green
 *       when on, grey when off.</li>
 *   <li>{@link CommandMenuEntry.Split} — two side-by-side buttons at
 *       {@code leftFraction}/{@code 1-leftFraction} of the panel width.
 *       Each half hovers independently; the hover highlight only colours
 *       the hovered sub-button.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = Dist.CLIENT
)
public final class CommandMenuRenderer {

    /** Alpha-blended POSITION_COLOR quads with depth test but no depth write. */
    private static final RenderType PANEL_QUAD = RenderType.create(
        DungeonTrain.MOD_ID + ":menu_quad",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        256,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(MenuRenderStates.SHADER_POSITION_COLOR)
            .setTransparencyState(MenuRenderStates.TRANSPARENCY_TRANSLUCENT)
            .setCullState(MenuRenderStates.CULL_DISABLED)
            .setDepthTestState(MenuRenderStates.DEPTH_LEQUAL)
            .setWriteMaskState(MenuRenderStates.WRITE_COLOR_ONLY)
            .createCompositeState(false)
    );

    private CommandMenuRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!CommandMenuState.isOpen()) return;

        // Refresh hover using the current frame's camera — between ticks
        // the crosshair keeps moving, so this keeps the highlight glued
        // to the player's aim.
        CommandMenuRaycast.updateHovered();

        Minecraft mc = Minecraft.getInstance();

        // Kill the crosshair hit for the upcoming tick. gameRenderer.pick()
        // runs before this event, so clobbering hitResult here means the
        // NEXT tick's continueAttack / block-highlight render will see a
        // MISS — blocks behind the panel stop being targetable or mineable
        // while the menu is open.
        if (mc.player != null) {
            mc.hitResult = BlockHitResult.miss(
                mc.player.getEyePosition(),
                Direction.UP,
                mc.player.blockPosition()
            );
        }

        List<CommandMenuEntry> entries = CommandMenuState.entries();
        if (entries.isEmpty()) return;

        Font font = mc.font;

        PoseStack poseStack = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        Vec3 anchor = CommandMenuState.anchorPos();
        Vec3 right = CommandMenuState.anchorRight();
        Vec3 up = CommandMenuState.anchorUp();
        Vec3 normal = CommandMenuState.anchorNormal();

        poseStack.pushPose();
        poseStack.translate(anchor.x - cam.x, anchor.y - cam.y, anchor.z - cam.z);

        Matrix3f basis = new Matrix3f(
            (float) right.x, (float) right.y, (float) right.z,
            (float) up.x, (float) up.y, (float) up.z,
            (float) normal.x, (float) normal.y, (float) normal.z
        );
        Quaternionf rot = new Quaternionf().setFromNormalized(basis);
        poseStack.mulPose(rot);

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        int count = entries.size();
        drawPanelBackdrop(poseStack, buffer, count);

        int hovered = CommandMenuState.hoveredIdx();
        int hoveredSub = CommandMenuState.hoveredSubIdx();
        for (int i = 0; i < count; i++) {
            drawRow(poseStack, buffer, font, entries.get(i), i, count,
                hovered == i, hoveredSub);
        }

        drawHeader(poseStack, buffer, font, count);

        // Typing field is rendered inline at the originating row by drawRow /
        // drawSplitRow — see isTypingHere(). No top-of-panel field anymore.

        buffer.endBatch(PANEL_QUAD);
        poseStack.popPose();
    }

    private static void drawPanelBackdrop(PoseStack poseStack, MultiBufferSource buffer, int entryCount) {
        float halfW = (float) (CommandMenuLayout.PANEL_WIDTH / 2.0);
        float halfH = (float) (CommandMenuLayout.totalHeight(entryCount) / 2.0);
        drawQuad(poseStack, buffer, -halfW, -halfH, halfW, halfH, 0x90000000);
    }

    private static void drawRow(
        PoseStack poseStack, MultiBufferSource buffer, Font font,
        CommandMenuEntry entry, int rowIndex, int count,
        boolean hovered, int hoveredSub
    ) {
        if (entry instanceof CommandMenuEntry.Split split) {
            drawSplitRow(poseStack, buffer, font, split, rowIndex, count, hovered, hoveredSub);
            return;
        }
        if (entry instanceof CommandMenuEntry.Triple triple) {
            drawTripleRow(poseStack, buffer, font, triple, rowIndex, count, hovered, hoveredSub);
            return;
        }

        float halfW = (float) (CommandMenuLayout.PANEL_WIDTH / 2.0);
        float halfH = (float) (CommandMenuLayout.ROW_HEIGHT / 2.0);
        float cy = (float) CommandMenuLayout.rowCenterY(rowIndex, count);
        float padX = 0.02f;
        float padY = 0.005f;

        boolean typingHere = isTypingHere(rowIndex, 0);

        // Base row tint — Toggle rows get a persistent colour to advertise state;
        // everything else is transparent so only the hover highlight colours.
        int baseTint = baseTintFor(entry);
        if (baseTint != 0 && !typingHere) {
            drawQuad(poseStack, buffer,
                -halfW + padX, cy - halfH + padY,
                halfW - padX, cy + halfH - padY,
                baseTint);
        }

        if (typingHere) {
            // Green-tinted backdrop with the buffer + cursor in place of the label,
            // so the user types into the same spot they clicked.
            drawQuad(poseStack, buffer,
                -halfW + padX, cy - halfH + padY,
                halfW - padX, cy + halfH - padY,
                TYPING_BACKDROP);
            drawCenteredText(poseStack, buffer, font,
                CommandMenuState.typedBuffer() + "_",
                0f, cy, 0xFF000000);
            return;
        }

        if (hovered) {
            drawQuad(poseStack, buffer,
                -halfW + padX, cy - halfH + padY,
                halfW - padX, cy + halfH - padY,
                0xB0FFCC33);
        }

        String label = labelFor(entry);
        int textColor = hovered ? 0xFF000000 : 0xFFFFFFFF;
        drawCenteredText(poseStack, buffer, font, label, 0f, cy, textColor);
    }

    private static void drawSplitRow(
        PoseStack poseStack, MultiBufferSource buffer, Font font,
        CommandMenuEntry.Split split, int rowIndex, int count,
        boolean hovered, int hoveredSub
    ) {
        float halfW = (float) (CommandMenuLayout.PANEL_WIDTH / 2.0);
        float halfH = (float) (CommandMenuLayout.ROW_HEIGHT / 2.0);
        float cy = (float) CommandMenuLayout.rowCenterY(rowIndex, count);
        float padX = 0.02f;
        float padY = 0.005f;
        float gap = 0.015f;

        float leftStart = -halfW + padX;
        float splitX = (float) (-halfW + split.leftFraction() * CommandMenuLayout.PANEL_WIDTH);
        float rightEnd = halfW - padX;

        boolean typingLeft = isTypingHere(rowIndex, 0);
        boolean typingRight = isTypingHere(rowIndex, 1);

        // Left half
        float leftEnd = splitX - gap / 2f;
        if (typingLeft) {
            drawQuad(poseStack, buffer,
                leftStart, cy - halfH + padY,
                leftEnd, cy + halfH - padY,
                TYPING_BACKDROP);
        } else if (hovered && hoveredSub == 0) {
            drawQuad(poseStack, buffer,
                leftStart, cy - halfH + padY,
                leftEnd, cy + halfH - padY,
                0xB0FFCC33);
        } else {
            drawQuad(poseStack, buffer,
                leftStart, cy - halfH + padY,
                leftEnd, cy + halfH - padY,
                0x30FFFFFF);
        }
        float leftCenterX = (leftStart + leftEnd) / 2f;
        String leftText = typingLeft ? CommandMenuState.typedBuffer() + "_" : labelFor(split.leftEntry());
        int leftColor = (typingLeft || (hovered && hoveredSub == 0)) ? 0xFF000000 : 0xFFFFFFFF;
        drawCenteredText(poseStack, buffer, font, leftText, leftCenterX, cy, leftColor);

        // Right half
        float rightStart = splitX + gap / 2f;
        if (typingRight) {
            drawQuad(poseStack, buffer,
                rightStart, cy - halfH + padY,
                rightEnd, cy + halfH - padY,
                TYPING_BACKDROP);
        } else if (hovered && hoveredSub == 1) {
            drawQuad(poseStack, buffer,
                rightStart, cy - halfH + padY,
                rightEnd, cy + halfH - padY,
                0xB0FFCC33);
        } else {
            drawQuad(poseStack, buffer,
                rightStart, cy - halfH + padY,
                rightEnd, cy + halfH - padY,
                0x30FFFFFF);
        }
        float rightCenterX = (rightStart + rightEnd) / 2f;
        String rightText = typingRight ? CommandMenuState.typedBuffer() + "_" : labelFor(split.rightEntry());
        int rightColor = (typingRight || (hovered && hoveredSub == 1)) ? 0xFF000000 : 0xFFFFFFFF;
        drawCenteredText(poseStack, buffer, font, rightText, rightCenterX, cy, rightColor);
    }

    /** Backdrop colour for the row currently capturing typed input. Same hue family as the legacy top-of-panel typing line, lower alpha so the text stays legible. */
    private static final int TYPING_BACKDROP = 0xB033FF99;

    private static boolean isTypingHere(int rowIdx, int subIdx) {
        return CommandMenuState.typingMode()
            && CommandMenuState.typingOriginRowIdx() == rowIdx
            && CommandMenuState.typingOriginSubIdx() == subIdx;
    }

    private static void drawTripleRow(
        PoseStack poseStack, MultiBufferSource buffer, Font font,
        CommandMenuEntry.Triple triple, int rowIndex, int count,
        boolean hovered, int hoveredSub
    ) {
        float halfW = (float) (CommandMenuLayout.PANEL_WIDTH / 2.0);
        float halfH = (float) (CommandMenuLayout.ROW_HEIGHT / 2.0);
        float cy = (float) CommandMenuLayout.rowCenterY(rowIndex, count);
        float padX = 0.02f;
        float padY = 0.005f;
        float gap = 0.015f;

        float leftBoundary  = (float) (-halfW + triple.leftFraction() * CommandMenuLayout.PANEL_WIDTH);
        float rightBoundary = (float) (-halfW + triple.middleEnd()    * CommandMenuLayout.PANEL_WIDTH);

        drawTripleCell(poseStack, buffer, font, triple.leftEntry(),
            -halfW + padX, leftBoundary - gap / 2f, cy, halfH, padY,
            hovered && hoveredSub == 0);
        drawTripleCell(poseStack, buffer, font, triple.middleEntry(),
            leftBoundary + gap / 2f, rightBoundary - gap / 2f, cy, halfH, padY,
            hovered && hoveredSub == 1);
        drawTripleCell(poseStack, buffer, font, triple.rightEntry(),
            rightBoundary + gap / 2f, halfW - padX, cy, halfH, padY,
            hovered && hoveredSub == 2);
    }

    private static void drawTripleCell(
        PoseStack poseStack, MultiBufferSource buffer, Font font,
        CommandMenuEntry entry,
        float xStart, float xEnd, float cy, float halfH, float padY,
        boolean hovered
    ) {
        int tint = hovered ? 0xB0FFCC33 : 0x30FFFFFF;
        drawQuad(poseStack, buffer,
            xStart, cy - halfH + padY,
            xEnd,   cy + halfH - padY,
            tint);
        float centerX = (xStart + xEnd) / 2f;
        drawCenteredText(poseStack, buffer, font, labelFor(entry),
            centerX, cy, hovered ? 0xFF000000 : 0xFFFFFFFF);
    }

    private static int baseTintFor(CommandMenuEntry entry) {
        if (entry instanceof CommandMenuEntry.Toggle t) {
            // Green when on, grey when off — state is immediately legible.
            return t.state() ? 0x8040AA40 : 0x40FFFFFF;
        }
        return 0;
    }

    private static String labelFor(CommandMenuEntry entry) {
        if (entry instanceof CommandMenuEntry.Toggle t) {
            return t.label() + (t.state() ? " [ON]" : " [OFF]");
        }
        return entry.label();
    }

    private static void drawHeader(PoseStack poseStack, MultiBufferSource buffer, Font font, int count) {
        String breadcrumb = CommandMenuState.breadcrumb();
        if (breadcrumb.isEmpty()) breadcrumb = "Dungeon Train";
        float cy = (float) CommandMenuLayout.headerCenterY(count);
        drawCenteredText(poseStack, buffer, font, breadcrumb, 0f, cy, 0xFFFFEEBB);
    }

    private static void drawCenteredText(
        PoseStack poseStack, MultiBufferSource buffer, Font font,
        String text, float worldX, float worldY, int color
    ) {
        poseStack.pushPose();
        poseStack.translate(worldX, worldY, 0.001f); // tiny bump out to avoid z-fight with backdrop
        float scale = (float) CommandMenuLayout.TEXT_SCALE;
        poseStack.scale(scale, -scale, scale);
        int textWidth = font.width(text);
        float x = -textWidth / 2f;
        float y = -font.lineHeight / 2f;
        Matrix4f mat = poseStack.last().pose();
        font.drawInBatch(
            text,
            x, y,
            color,
            false,
            mat,
            buffer,
            Font.DisplayMode.SEE_THROUGH,
            0,
            LightTexture.FULL_BRIGHT
        );
        poseStack.popPose();
    }

    private static void drawQuad(
        PoseStack poseStack, MultiBufferSource buffer,
        float x1, float y1, float x2, float y2, int argb
    ) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        VertexConsumer vc = buffer.getBuffer(PANEL_QUAD);
        Matrix4f mat = poseStack.last().pose();
        vc.vertex(mat, x1, y1, 0).color(r, g, b, a).endVertex();
        vc.vertex(mat, x2, y1, 0).color(r, g, b, a).endVertex();
        vc.vertex(mat, x2, y2, 0).color(r, g, b, a).endVertex();
        vc.vertex(mat, x1, y2, 0).color(r, g, b, a).endVertex();
    }
}
