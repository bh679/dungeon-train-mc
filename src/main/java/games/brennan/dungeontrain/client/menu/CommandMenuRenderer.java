package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
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
import net.neoforged.fml.common.EventBusSubscriber;
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
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
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

        // Re-anchor every frame against the partial-tick interpolated eye so
        // the panel tracks player translation smoothly. Must run before the
        // raycast below so hover detection sees the same anchor as the draw.
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        CommandMenuState.refreshAnchorForFrame(partialTick);

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

        // Apply the world-space display-scale uniformly to the whole panel
        // (backdrop quad, row tints, hover highlights, text, breadcrumb).
        // Matched on the input side by {@link CommandMenuRaycast}, which
        // divides its hit point by the same factor before comparing
        // against the unscaled layout constants.
        float worldScale = (float) ClientDisplayConfig.getWorldspaceScale();
        if (worldScale != 1.0f) {
            poseStack.scale(worldScale, worldScale, worldScale);
        }

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        int count = entries.size();
        double mainPanelW = CommandMenuState.mainPanelWidth();
        drawPanelBackdrop(poseStack, buffer, count, mainPanelW);

        int hovered = CommandMenuState.hoveredIdx();
        int hoveredSub = CommandMenuState.hoveredSubIdx();
        for (int i = 0; i < count; i++) {
            drawRow(poseStack, buffer, font, entries.get(i), i, count,
                hovered == i, hoveredSub, mainPanelW);
        }

        drawHeader(poseStack, buffer, font, count, CommandMenuState.breadcrumb(), mainPanelW);

        // Typing field is rendered inline at the originating row by drawRow /
        // drawSplitRow — see isTypingHere(). No top-of-panel field anymore.

        // Side panel — drawn translated +X by the main panel's full width plus
        // a small inter-panel gap. Uses the same row primitives so hover
        // tints, base colours, and text alignment match the main panel
        // exactly. Side-panel rebuild is driven by CommandMenuState — see
        // MenuScreen.sidePanel for the data path.
        if (CommandMenuState.hasSidePanel()) {
            drawSidePanel(poseStack, buffer, font);
        }

        buffer.endBatch(PANEL_QUAD);
        poseStack.popPose();
    }

    private static void drawSidePanel(
        PoseStack poseStack, MultiBufferSource buffer, Font font
    ) {
        List<CommandMenuEntry> sideEntries = CommandMenuState.sideEntries();
        if (sideEntries.isEmpty()) return;

        // Translate to the right of the *main* panel, regardless of the side
        // panel's own width — the main panel's width is what determines the
        // gap between the two pieces of UI.
        double sideOffset = CommandMenuState.mainPanelWidth() / 2.0
            + CommandMenuState.sidePanelWidth() / 2.0
            + SIDE_PANEL_GAP;
        poseStack.pushPose();
        poseStack.translate(sideOffset, 0.0, 0.0);

        int sideCount = sideEntries.size();
        double sidePanelW = CommandMenuState.sidePanelWidth();
        drawPanelBackdrop(poseStack, buffer, sideCount, sidePanelW);

        int sideHovered = CommandMenuState.sideHoveredIdx();
        int sideHoveredSub = CommandMenuState.sideHoveredSubIdx();
        for (int i = 0; i < sideCount; i++) {
            drawRow(poseStack, buffer, font, sideEntries.get(i), i, sideCount,
                sideHovered == i, sideHoveredSub, sidePanelW);
        }

        MenuScreen side = CommandMenuState.sideScreen();
        String title = side != null ? side.title() : "";
        drawHeader(poseStack, buffer, font, sideCount, title, sidePanelW);

        poseStack.popPose();
    }

    /** Horizontal gap between the main panel's right edge and the side panel's left edge, in world units. */
    static final double SIDE_PANEL_GAP = 0.12;

    private static void drawPanelBackdrop(PoseStack poseStack, MultiBufferSource buffer, int entryCount, double panelWidth) {
        float halfW = (float) (panelWidth / 2.0);
        float halfH = (float) (CommandMenuLayout.totalHeight(entryCount) / 2.0);
        drawQuad(poseStack, buffer, -halfW, -halfH, halfW, halfH, 0x90000000);
    }

    private static void drawRow(
        PoseStack poseStack, MultiBufferSource buffer, Font font,
        CommandMenuEntry entry, int rowIndex, int count,
        boolean hovered, int hoveredSub, double panelWidth
    ) {
        if (entry instanceof CommandMenuEntry.Split split) {
            drawSplitRow(poseStack, buffer, font, split, rowIndex, count, hovered, hoveredSub, panelWidth);
            return;
        }
        if (entry instanceof CommandMenuEntry.Quad quad) {
            drawQuadRow(poseStack, buffer, font, quad, rowIndex, count, hovered, hoveredSub, panelWidth);
            return;
        }
        if (entry instanceof CommandMenuEntry.Triple triple) {
            drawTripleRow(poseStack, buffer, font, triple, rowIndex, count, hovered, hoveredSub, panelWidth);
            return;
        }

        float halfW = (float) (panelWidth / 2.0);
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
        boolean hovered, int hoveredSub, double panelWidth
    ) {
        float halfW = (float) (panelWidth / 2.0);
        float halfH = (float) (CommandMenuLayout.ROW_HEIGHT / 2.0);
        float cy = (float) CommandMenuLayout.rowCenterY(rowIndex, count);
        float padX = 0.02f;
        float padY = 0.005f;
        float gap = 0.015f;

        float leftStart = -halfW + padX;
        float splitX = (float) (-halfW + split.leftFraction() * panelWidth);
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
            int leftAccent = splitHalfAccentFor(split.leftEntry());
            drawQuad(poseStack, buffer,
                leftStart, cy - halfH + padY,
                leftEnd, cy + halfH - padY,
                leftAccent != 0 ? leftAccent : 0x30FFFFFF);
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
            int rightAccent = splitHalfAccentFor(split.rightEntry());
            drawQuad(poseStack, buffer,
                rightStart, cy - halfH + padY,
                rightEnd, cy + halfH - padY,
                rightAccent != 0 ? rightAccent : 0x30FFFFFF);
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
        boolean hovered, int hoveredSub, double panelWidth
    ) {
        float halfW = (float) (panelWidth / 2.0);
        float halfH = (float) (CommandMenuLayout.ROW_HEIGHT / 2.0);
        float cy = (float) CommandMenuLayout.rowCenterY(rowIndex, count);
        float padX = 0.02f;
        float padY = 0.005f;
        float gap = 0.015f;

        float leftBoundary  = (float) (-halfW + triple.leftFraction() * panelWidth);
        float rightBoundary = (float) (-halfW + triple.middleEnd()    * panelWidth);

        drawTripleCell(poseStack, buffer, font, triple.leftEntry(),
            -halfW + padX, leftBoundary - gap / 2f, cy, halfH, padY,
            hovered && hoveredSub == 0, isTypingHere(rowIndex, 0));
        drawTripleCell(poseStack, buffer, font, triple.middleEntry(),
            leftBoundary + gap / 2f, rightBoundary - gap / 2f, cy, halfH, padY,
            hovered && hoveredSub == 1, isTypingHere(rowIndex, 1));
        drawTripleCell(poseStack, buffer, font, triple.rightEntry(),
            rightBoundary + gap / 2f, halfW - padX, cy, halfH, padY,
            hovered && hoveredSub == 2, isTypingHere(rowIndex, 2));
    }

    private static void drawQuadRow(
        PoseStack poseStack, MultiBufferSource buffer, Font font,
        CommandMenuEntry.Quad quad, int rowIndex, int count,
        boolean hovered, int hoveredSub, double panelWidth
    ) {
        float halfW = (float) (panelWidth / 2.0);
        float halfH = (float) (CommandMenuLayout.ROW_HEIGHT / 2.0);
        float cy = (float) CommandMenuLayout.rowCenterY(rowIndex, count);
        float padX = 0.02f;
        float padY = 0.005f;
        float gap = 0.012f;

        float b1 = (float) (-halfW + quad.boundary1() * panelWidth);
        float b2 = (float) (-halfW + quad.boundary2() * panelWidth);
        float b3 = (float) (-halfW + quad.boundary3() * panelWidth);

        drawTripleCell(poseStack, buffer, font, quad.e1(),
            -halfW + padX, b1 - gap / 2f, cy, halfH, padY,
            hovered && hoveredSub == 0, isTypingHere(rowIndex, 0));
        drawTripleCell(poseStack, buffer, font, quad.e2(),
            b1 + gap / 2f, b2 - gap / 2f, cy, halfH, padY,
            hovered && hoveredSub == 1, isTypingHere(rowIndex, 1));
        drawTripleCell(poseStack, buffer, font, quad.e3(),
            b2 + gap / 2f, b3 - gap / 2f, cy, halfH, padY,
            hovered && hoveredSub == 2, isTypingHere(rowIndex, 2));
        drawTripleCell(poseStack, buffer, font, quad.e4(),
            b3 + gap / 2f, halfW - padX, cy, halfH, padY,
            hovered && hoveredSub == 3, isTypingHere(rowIndex, 3));
    }

    private static void drawTripleCell(
        PoseStack poseStack, MultiBufferSource buffer, Font font,
        CommandMenuEntry entry,
        float xStart, float xEnd, float cy, float halfH, float padY,
        boolean hovered, boolean typing
    ) {
        // Typing cell wins over normal label rendering — draw the
        // type-buffer with a green backdrop in place of the label, so the
        // user sees their input land in the same cell they clicked.
        if (typing) {
            drawQuad(poseStack, buffer,
                xStart, cy - halfH + padY,
                xEnd,   cy + halfH - padY,
                TYPING_BACKDROP);
            float centerX = (xStart + xEnd) / 2f;
            drawCenteredText(poseStack, buffer, font,
                CommandMenuState.typedBuffer() + "_",
                centerX, cy, 0xFF000000);
            return;
        }

        boolean isLabel = entry instanceof CommandMenuEntry.Label;
        int baseTint = baseTintFor(entry);
        int tint;
        if (hovered && !isLabel) {
            tint = 0xB0FFCC33;
        } else if (baseTint != 0) {
            tint = baseTint;
        } else if (isLabel) {
            tint = 0;
        } else {
            tint = 0x30FFFFFF;
        }
        if (tint != 0) {
            drawQuad(poseStack, buffer,
                xStart, cy - halfH + padY,
                xEnd,   cy + halfH - padY,
                tint);
        }
        float centerX = (xStart + xEnd) / 2f;
        drawCenteredText(poseStack, buffer, font, labelFor(entry),
            centerX, cy, hovered && !isLabel ? 0xFF000000 : 0xFFFFFFFF);
    }

    private static int baseTintFor(CommandMenuEntry entry) {
        if (entry instanceof CommandMenuEntry.Toggle t) {
            // Green when on, grey when off — state is immediately legible.
            return t.state() ? 0x8040AA40 : 0x40FFFFFF;
        }
        if (entry instanceof CommandMenuEntry.SaveAction s) {
            // Green when actionable, grey when already saved.
            return s.saved() ? 0x40808080 : 0x8040AA40;
        }
        if (entry instanceof CommandMenuEntry.Label) {
            // No backdrop — Label rows are pure text on the panel surface.
            return 0;
        }
        if (entry instanceof CommandMenuEntry.Run r && r.highlighted()) {
            // Soft amber accent — "this is the option you're currently in".
            return 0x80FFAA33;
        }
        if (entry instanceof CommandMenuEntry.Stay s && s.highlighted()) {
            return 0x80FFAA33;
        }
        if (entry instanceof CommandMenuEntry.DrillIn d && d.highlighted()) {
            return 0x80FFAA33;
        }
        return 0;
    }

    /**
     * Persistent "you're here" amber accent for a highlighted {@link CommandMenuEntry.Run}/
     * {@link CommandMenuEntry.Stay}/{@link CommandMenuEntry.DrillIn} — the same tint full-width rows
     * use — so a highlighted half of a {@link CommandMenuEntry.Split} reads identically. Returns 0 for
     * everything else (including Toggle/SaveAction, whose own tints {@code drawSplitRow} leaves alone).
     */
    private static int splitHalfAccentFor(CommandMenuEntry entry) {
        if (entry instanceof CommandMenuEntry.Run r && r.highlighted()) return 0x80FFAA33;
        if (entry instanceof CommandMenuEntry.Stay s && s.highlighted()) return 0x80FFAA33;
        if (entry instanceof CommandMenuEntry.DrillIn d && d.highlighted()) return 0x80FFAA33;
        return 0;
    }

    private static String labelFor(CommandMenuEntry entry) {
        if (entry instanceof CommandMenuEntry.Toggle t) {
            // Compact toggles convey state by the green/grey cell tint alone.
            return t.showStateText() ? t.label() + (t.state() ? " [ON]" : " [OFF]") : t.label();
        }
        return entry.label();
    }

    private static void drawHeader(PoseStack poseStack, MultiBufferSource buffer, Font font, int count, String title, double panelWidth) {
        // panelWidth currently unused — text is centered on x=0 within the
        // panel's own coordinate frame so it scales with the panel width
        // naturally. Threaded through to stay consistent with the other
        // draw helpers and to leave room for left/right-aligned headers
        // in the future.
        String header = title;
        if (header == null || header.isEmpty()) header = "Dungeon Train";
        float cy = (float) CommandMenuLayout.headerCenterY(count);
        drawCenteredText(poseStack, buffer, font, header, 0f, cy, 0xFFFFEEBB);
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
        vc.addVertex(mat, x1, y1, 0f).setColor(r, g, b, a);
        vc.addVertex(mat, x2, y1, 0f).setColor(r, g, b, a);
        vc.addVertex(mat, x2, y2, 0f).setColor(r, g, b, a);
        vc.addVertex(mat, x1, y2, 0f).setColor(r, g, b, a);
    }
}
