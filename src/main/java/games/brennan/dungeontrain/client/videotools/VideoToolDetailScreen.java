package games.brennan.dungeontrain.client.videotools;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * One filming command in full: its clip playing at the top of the column, the command, then the
 * body copy from {@link VideoTool#bodyKeys()}. Opened by clicking a tile on
 * {@link VideoToolsScreen}, and Done returns there.
 *
 * <p>Same scroll/viewport machinery as the hub — laid out once into canvas-relative
 * {@link Line}s, drawn scissor-clipped and offset by {@link #scrollY}. No inline links here, so
 * there is no click hit-testing to do.</p>
 */
public final class VideoToolDetailScreen extends Screen {

    private static final int MAX_COL_W = 360;
    private static final int SIDE_MARGIN = 40;
    private static final int PANEL_PAD = 10;
    private static final int TOP = 16;
    private static final int PARA_GAP = 6;
    private static final int SCROLL_STEP = 12;

    private static final int COLOUR_PANEL = 0xC0101010;
    private static final int COLOUR_HEADER = 0xFFFFFFFF;
    private static final int COLOUR_DESC = 0xFFCACACA;
    private static final int COLOUR_COMMAND = 0xFFFFD37F;
    private static final int COLOUR_CLIP_EDGE = 0xFF3A3A3A;

    private final Screen parent;
    private final VideoTool tool;

    private int colX;
    private int colW;
    private int clipY;
    private int clipH;
    private int viewportTop;
    private int viewportBottom;
    private int scrollY;
    private int maxScroll;
    private final List<Line> lines = new ArrayList<>();

    private record Line(FormattedCharSequence text, int canvasY, int colour) {}

    public VideoToolDetailScreen(Screen parent, VideoTool tool) {
        super(tool.header());
        this.parent = parent;
        this.tool = tool;
    }

    @Override
    protected void init() {
        lines.clear();
        colW = Math.min(MAX_COL_W, this.width - SIDE_MARGIN);
        colX = (this.width - colW) / 2;
        int lh = this.font.lineHeight;

        int y = 0;
        y = add(this.title, y, lh, COLOUR_HEADER);
        y += 4;

        // The clip, full column width at the sheet's 8:5.
        clipY = y;
        clipH = colW * VideoTool.FRAME_H / VideoTool.FRAME_W;
        y += clipH + 6;

        y = add(Component.literal(tool.command()), y, lh, COLOUR_COMMAND);
        y += 3;
        for (String key : tool.bodyKeys()) {
            y = add(tool.body(key), y, lh, COLOUR_DESC);
            y += PARA_GAP;
        }

        int contentHeight = y;
        int rowY = this.height - 28;
        viewportTop = TOP;
        viewportBottom = rowY - 8;
        if (viewportBottom < viewportTop) {
            viewportBottom = viewportTop;
        }
        maxScroll = Math.max(0, contentHeight - (viewportBottom - viewportTop));
        scrollY = Mth.clamp(scrollY, 0, maxScroll);

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds((this.width - 100) / 2, rowY, 100, 20)
                .build());
    }

    private int add(Component text, int y, int lh, int colour) {
        for (FormattedCharSequence line : this.font.split(text, colW)) {
            lines.add(new Line(line, y, colour));
            y += lh;
        }
        return y;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0) {
            this.scrollY = Mth.clamp(this.scrollY - (int) (scrollY * SCROLL_STEP), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(colX - PANEL_PAD, viewportTop - PANEL_PAD,
                colX + colW + PANEL_PAD, viewportBottom + PANEL_PAD, COLOUR_PANEL);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int lh = this.font.lineHeight;
        g.enableScissor(colX - PANEL_PAD, viewportTop, colX + colW + PANEL_PAD, viewportBottom);

        int clipDrawY = viewportTop + clipY - scrollY;
        if (clipDrawY + clipH >= viewportTop && clipDrawY <= viewportBottom) {
            AnimatedSheet.draw(g, tool, colX, clipDrawY, colW, clipH);
            g.renderOutline(colX - 1, clipDrawY - 1, colW + 2, clipH + 2, COLOUR_CLIP_EDGE);
        }
        for (Line line : lines) {
            int drawY = viewportTop + line.canvasY() - scrollY;
            if (drawY + lh < viewportTop || drawY > viewportBottom) {
                continue; // cull off-viewport lines
            }
            g.drawString(this.font, line.text(), colX, drawY, line.colour(), false);
        }
        g.disableScissor();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
