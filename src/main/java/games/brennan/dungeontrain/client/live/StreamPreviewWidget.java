package games.brennan.dungeontrain.client.live;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.client.live.StreamPreviewRegions.Part;
import games.brennan.dungeontrain.client.live.StreamPreviewRegions.Rect;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Stream tab's preview: what viewers would see right now, given the tab's settings. Fits the
 * largest 16:9 picture into its box under a caption naming the video size. The frame is held at
 * that size and drawn unsmoothed, so a smaller size looks as blocky here as it will to viewers.
 * The live feed pinned top-right is outlined at its chosen size: it is on your screen, not theirs.
 *
 * <p>Clicking the HUD, the chat, the name tag or that outline flips the same setting as its Stream-tab
 * row ({@link Part}); hovering one outlines it and names what a click does. A part Kid mode (or Live
 * chat off) locks is outlined grey and says why instead. {@code onToggled} lets the screen set the
 * matching row to the new value.</p>
 */
public final class StreamPreviewWidget extends AbstractWidget {

    private static final int BORDER = 0xFF5A5A5A;
    private static final int EMPTY = 0xFF101010;
    private static final int DIM = 0xB0000000;
    private static final int OUTLINE = 0xFFFFFFFF;
    private static final int OUTLINE_FILL = 0x50000000;
    private static final int HOVER_FILL = 0x30FFFFFF;
    private static final int LOCKED_OUTLINE = 0xFF808080;
    private static final int TOOLTIP_W = 200;
    private static final String CLICK_KEY = "gui.dungeontrain.options.stream.preview.click";

    private final Consumer<Part> onToggled;
    /** The last drawn picture's mapping from GUI pixels to the preview, or null while there is no picture. */
    @Nullable private Mapping mapping;

    /** GUI px → preview px: {@code px = x0 + (gx - left) * s}. */
    private record Mapping(int x0, int y0, int w, int h, float left, float top, float s, StreamPreviewRegions regions) {
        float guiX(double px) { return left + (float) (px - x0) / s; }
        float guiY(double py) { return top + (float) (py - y0) / s; }
        boolean inside(double px, double py) { return px >= x0 && px < x0 + w && py >= y0 && py < y0 + h; }
    }

    public StreamPreviewWidget(Consumer<Part> onToggled) {
        super(0, 0, 0, 0, Component.translatable("gui.dungeontrain.options.stream.preview"));
        this.onToggled = onToggled;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        StreamPreview.request();
        Font font = Minecraft.getInstance().font;
        int captionH = font.lineHeight + 4;
        int availW = getWidth();
        int availH = getHeight() - captionH;
        if (availW < 32 || availH < 18) {
            this.mapping = null; // no picture, nothing to click
            return;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int[] size = StreamPreview.frameSize(main); // the broadcast's own shape, never stretched
        int w = Math.min(availW, availH * size[0] / size[1]);
        int h = w * size[1] / size[0];
        int x0 = getX() + (availW - w) / 2;
        int y0 = getY() + captionH;

        g.drawCenteredString(font, caption(size), getX() + availW / 2, getY(), 0xFFFFFF);
        g.fill(x0 - 1, y0 - 1, x0 + w + 1, y0 + h + 1, BORDER);

        TextureTarget frame = StreamPreview.frame();
        if (Minecraft.getInstance().level == null || frame == null) {
            this.mapping = null;
            g.fill(x0, y0, x0 + w, y0 + h, EMPTY);
            centred(g, font, Component.translatable("gui.dungeontrain.options.stream.preview.no_world"), x0, y0, w, h);
            return;
        }
        drawFrame(g, frame, x0, y0, w, h);
        this.mapping = mapping(g, font, main, size, x0, y0, w, h);
        if (this.mapping != null) {
            // The live feed pinned top-right, an outline only: it is on your screen, never in the broadcast.
            int[] box = toPreview(this.mapping, this.mapping.regions().rect(Part.HEAD_VIEWER));
            g.fill(box[0], box[1], box[0] + box[2], box[1] + box[3], OUTLINE_FILL);
            g.renderOutline(box[0], box[1], box[2], box[3], OUTLINE);
        }
        if (!LiveFeedClientConfig.streamingEnabled()) {
            g.fill(x0, y0, x0 + w, y0 + h, DIM);
            centred(g, font, Component.translatable("gui.dungeontrain.options.stream.preview.off"), x0, y0, w, h);
        }
        Part hovered = partAt(mouseX, mouseY);
        if (hovered != null) hover(g, font, hovered);
    }

    /**
     * Your screen's GUI pixels mapped into the preview through the same crop as the broadcast, with the
     * clickable parts where they sit on your screen: the pinned feed at its streaming size, as tall as your
     * own broadcast's shape.
     */
    @Nullable
    private static Mapping mapping(GuiGraphics g, Font font, RenderTarget main, int[] size, int x0, int y0, int w, int h) {
        int guiW = g.guiWidth();
        int guiH = g.guiHeight();
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        if (guiW <= 0 || guiH <= 0 || guiScale <= 0) return null;
        int[] crop = LiveAspect.cropRect(main.width, main.height, size[0], size[1]);
        float s = (float) (w * guiScale / (crop[2] - crop[0])); // preview px per GUI px
        float left = (float) (crop[0] / guiScale);
        float top = (float) ((main.height - crop[3]) / guiScale);
        LiveHeadViewer.Box b = LiveHeadViewer.layout(guiW, LiveFeedClientConfig.headViewerWidth(true), (float) size[0] / size[1]);
        ChatComponent chat = Minecraft.getInstance().gui.getChat();
        float chatScale = (float) chat.getScale();
        StreamPreviewRegions regions = StreamPreviewRegions.of(guiW, guiH, new Rect(b.x(), b.y(), b.w(), b.h()),
                StreamNameTag.pillGuiWidth(font), StreamNameTag.pillGuiHeight(),
                chat.getWidth(), chat.getHeight() * chatScale); // vanilla scales the lines, not the box's width
        return new Mapping(x0, y0, w, h, left, top, s, regions);
    }

    /** A GUI-pixel rectangle in preview pixels, as {x, y, w, h}; never thinner than 2. */
    private static int[] toPreview(Mapping m, Rect r) {
        return new int[] {m.x0() + Math.round((r.x() - m.left()) * m.s()), m.y0() + Math.round((r.y() - m.top()) * m.s()),
                Math.max(2, Math.round(r.w() * m.s())), Math.max(2, Math.round(r.h() * m.s()))};
    }

    /** The part under the mouse, or null for none or no picture. */
    @Nullable
    private Part partAt(double mouseX, double mouseY) {
        Mapping m = this.mapping;
        if (m == null || !this.visible || !m.inside(mouseX, mouseY)) return null;
        return m.regions().pick(m.guiX(mouseX), m.guiY(mouseY));
    }

    private void hover(GuiGraphics g, Font font, Part part) {
        boolean locked = lockedReason(part) != null;
        int[] r = toPreview(this.mapping, this.mapping.regions().rect(part));
        // Clipped to the picture: a part can reach past the broadcast's crop.
        g.enableScissor(this.mapping.x0(), this.mapping.y0(), this.mapping.x0() + this.mapping.w(), this.mapping.y0() + this.mapping.h());
        if (!locked) g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], HOVER_FILL);
        g.renderOutline(r[0], r[1], r[2], r[3], locked ? LOCKED_OUTLINE : OUTLINE);
        g.disableScissor();
        if (Minecraft.getInstance().screen != null) {
            Minecraft.getInstance().screen.setTooltipForNextRenderPass(tooltip(font, part));
        }
    }

    /** The row's name, then "Now On. Click for Off." or, for a locked part, why it is locked. */
    private static List<FormattedCharSequence> tooltip(Font font, Part part) {
        List<FormattedCharSequence> lines = new ArrayList<>(font.split(Component.translatable(rowKey(part)), TOOLTIP_W));
        Component locked = lockedReason(part);
        lines.addAll(font.split(locked != null ? locked : Component.translatable(CLICK_KEY, value(part), nextValue(part)), TOOLTIP_W));
        return lines;
    }

    /** The lang key of the Stream-tab row a part's click shares. */
    static String rowKey(Part part) {
        return switch (part) {
            case GUI -> "gui.dungeontrain.options.stream_gui";
            case CHAT -> "gui.dungeontrain.options.stream_chat";
            case NAME_TAG -> "gui.dungeontrain.options.stream_name_tag";
            case HEAD_VIEWER -> "gui.dungeontrain.options.head_viewer_size_streaming";
        };
    }

    /** Why a part's row is greyed, as that row says it; null while it can be changed. */
    @Nullable
    private static Component lockedReason(Part part) {
        boolean kid = ClientDisplayConfig.isKidMode();
        return switch (part) {
            case CHAT -> kid ? Component.translatable(rowKey(part) + ".kid_tip")
                    : !LiveFeedClientConfig.liveChat() ? Component.translatable(rowKey(part) + ".live_chat_off_tip") : null;
            case NAME_TAG -> kid ? Component.translatable(rowKey(part) + ".kid_tip") : null;
            default -> null;
        };
    }

    private static Component value(Part part) {
        return switch (part) {
            case GUI -> CommonComponents.optionStatus(LiveFeedClientConfig.streamGui());
            case CHAT -> CommonComponents.optionStatus(LiveFeedClientConfig.streamChat());
            case NAME_TAG -> nameTagLabel(LiveFeedClientConfig.streamNameTag());
            case HEAD_VIEWER -> sizeLabel(LiveFeedClientConfig.headViewerSize(true));
        };
    }

    private static Component nextValue(Part part) {
        return switch (part) {
            case GUI -> CommonComponents.optionStatus(!LiveFeedClientConfig.streamGui());
            case CHAT -> CommonComponents.optionStatus(!LiveFeedClientConfig.streamChat());
            case NAME_TAG -> nameTagLabel(nextNameTag(LiveFeedClientConfig.streamNameTag()));
            case HEAD_VIEWER -> sizeLabel(LiveFeedClientConfig.nextSize(LiveFeedClientConfig.headViewerSize(true)));
        };
    }

    /** The row's own order: Minecraft, Discord, None, and round again. */
    static LiveFeedClientConfig.NameTag nextNameTag(LiveFeedClientConfig.NameTag tag) {
        LiveFeedClientConfig.NameTag[] all = LiveFeedClientConfig.NameTag.values();
        return all[(tag.ordinal() + 1) % all.length];
    }

    private static Component nameTagLabel(LiveFeedClientConfig.NameTag tag) {
        return Component.translatable("gui.dungeontrain.options.stream_name_tag." + tag.name().toLowerCase(java.util.Locale.ROOT));
    }

    private static Component sizeLabel(int index) {
        return Component.literal(LiveFeedClientConfig.headViewerSizePercent(index) + "%");
    }

    /** Flip a part's setting, as its row would. */
    private static void toggle(Part part) {
        switch (part) {
            case GUI -> LiveFeedClientConfig.setStreamGui(!LiveFeedClientConfig.streamGui());
            case CHAT -> LiveFeedClientConfig.setStreamChat(!LiveFeedClientConfig.streamChat());
            case NAME_TAG -> LiveFeedClientConfig.setStreamNameTag(nextNameTag(LiveFeedClientConfig.streamNameTag()));
            case HEAD_VIEWER -> LiveFeedClientConfig.cycleHeadViewerSize(true);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!this.active || !this.visible || !isValidClickButton(button)) return false;
        Part part = partAt(mouseX, mouseY);
        if (part == null || lockedReason(part) != null) return false;
        toggle(part);
        playDownSound(Minecraft.getInstance().getSoundManager());
        this.onToggled.accept(part);
        return true;
    }

    /** "What viewers see · 960×720": the size the broadcast is sent at, which the picture shows. */
    private Component caption(int[] size) {
        return getMessage().copy().append(" · " + size[0] + "×" + size[1]);
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
