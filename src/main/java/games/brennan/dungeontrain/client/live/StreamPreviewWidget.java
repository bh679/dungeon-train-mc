package games.brennan.dungeontrain.client.live;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

/**
 * The Stream tab's preview: what viewers would see right now, given the tab's settings. Fits the
 * largest 16:9 picture into its box under a one-line caption. Not interactive.
 */
public final class StreamPreviewWidget extends AbstractWidget {

    private static final int BORDER = 0xFF5A5A5A;
    private static final int EMPTY = 0xFF101010;
    private static final int DIM = 0xB0000000;

    public StreamPreviewWidget() {
        super(0, 0, 0, 0, Component.translatable("gui.dungeontrain.options.stream.preview"));
        this.active = false;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        StreamPreview.request();
        Font font = Minecraft.getInstance().font;
        int captionH = font.lineHeight + 4;
        int availW = getWidth();
        int availH = getHeight() - captionH;
        if (availW < 32 || availH < 18) return;
        int w = Math.min(availW, availH * 16 / 9);
        int h = w * 9 / 16;
        int x0 = getX() + (availW - w) / 2;
        int y0 = getY() + captionH;

        g.drawCenteredString(font, getMessage(), getX() + availW / 2, getY(), 0xFFFFFF);
        g.fill(x0 - 1, y0 - 1, x0 + w + 1, y0 + h + 1, BORDER);

        TextureTarget frame = StreamPreview.frame();
        if (Minecraft.getInstance().level == null || frame == null) {
            g.fill(x0, y0, x0 + w, y0 + h, EMPTY);
            centred(g, font, Component.translatable("gui.dungeontrain.options.stream.preview.no_world"), x0, y0, w, h);
            return;
        }
        drawFrame(g, frame, x0, y0, w, h);
        if (!LiveFeedClientConfig.streamingEnabled()) {
            g.fill(x0, y0, x0 + w, y0 + h, DIM);
            centred(g, font, Component.translatable("gui.dungeontrain.options.stream.preview.off"), x0, y0, w, h);
        }
    }

    private static void centred(GuiGraphics g, Font font, Component text, int x0, int y0, int w, int h) {
        g.drawCenteredString(font, text, x0 + w / 2, y0 + (h - font.lineHeight) / 2, 0xFFFFFF);
    }

    /** The framebuffer texture is bottom-up, so v runs 1 → 0 down the quad. */
    private static void drawFrame(GuiGraphics g, TextureTarget frame, int x0, int y0, int w, int h) {
        g.flush();
        Matrix4f m = g.pose().last().pose();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, frame.getColorTextureId());
        RenderSystem.disableBlend();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        b.addVertex(m, x0, y0, 0).setUv(0, 1);
        b.addVertex(m, x0, y0 + h, 0).setUv(0, 0);
        b.addVertex(m, x0 + w, y0 + h, 0).setUv(1, 0);
        b.addVertex(m, x0 + w, y0, 0).setUv(1, 1);
        BufferUploader.drawWithShader(b.buildOrThrow());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
