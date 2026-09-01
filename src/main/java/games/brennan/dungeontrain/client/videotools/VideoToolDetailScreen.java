package games.brennan.dungeontrain.client.videotools;

import games.brennan.dungeontrain.client.ui.CardCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * One filming command in full: its clip playing at the top of the column, then a card holding the
 * command and the body copy from {@link VideoTool#bodyKeys()}. Opened by clicking a tile on
 * {@link VideoToolsScreen}, and Done returns there.
 *
 * <p>Same {@link CardCanvas} the hub uses, so the two pages cannot drift apart — including the clip
 * being drawn in the page's own scissored pass against {@link CardCanvas#screenY}, which is the one
 * thing the canvas does not own. No inline links here, so there is no click hit-testing to do.</p>
 */
public final class VideoToolDetailScreen extends Screen {

    private static final int MAX_COL_W = 360;
    private static final int SIDE_MARGIN = 40;
    private static final int TOP = 16;
    /** Between the clip and the card under it. */
    private static final int CLIP_GAP = 8;

    private static final int COLOUR_COMMAND = 0xFFFFD37F;
    private static final int COLOUR_CLIP_EDGE = 0xFF3A3A3A;
    /** The hub's commands accent, so a detail page reads as part of that card. */
    private static final int ACCENT_COMMAND = 0xFFE0B56A;

    private final Screen parent;
    private final VideoTool tool;
    private final CardCanvas canvas;

    private int clipY;
    private int clipH;

    public VideoToolDetailScreen(Screen parent, VideoTool tool) {
        super(tool.header());
        this.parent = parent;
        this.tool = tool;
        this.canvas = new CardCanvas(Minecraft.getInstance().font);
    }

    @Override
    protected void init() {
        int colW = Math.min(MAX_COL_W, this.width - SIDE_MARGIN);
        canvas.beginLayout((this.width - colW) / 2, colW);

        int y = 0;
        y = canvas.addCenteredWrapped(this.title, y, CardCanvas.COLOUR_HEADER);
        y += CardCanvas.PARA_GAP;

        // The clip, full column width at the sheet's 8:5. Un-carded — it frames the page the way
        // the hub's title does, and a card border around a bordered clip reads as noise.
        clipY = y;
        clipH = colW * VideoTool.FRAME_H / VideoTool.FRAME_W;
        y += clipH + CLIP_GAP;

        y = addBodyCard(y);

        // The viewport ends just above the Done button so scrolling content never overlaps it.
        int rowY = this.height - 28;
        canvas.finishLayout(y, TOP, rowY - 8);

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds((this.width - 100) / 2, rowY, 100, 20)
                .build());
    }

    /** The command as the card's heading, over its accent bar, then the body paragraphs. */
    private int addBodyCard(int top) {
        int innerX = canvas.colX() + CardCanvas.CARD_PAD;
        int innerW = Math.max(1, canvas.colW() - CardCanvas.CARD_PAD * 2);

        int y = canvas.addWrappedAt(Component.literal(tool.command()), innerX, innerW,
                top + CardCanvas.CARD_PAD, COLOUR_COMMAND);
        y += CardCanvas.RULE_GAP;
        y = canvas.addRule(innerX, y, Math.min(CardCanvas.RULE_W, innerW), ACCENT_COMMAND);
        y += CardCanvas.RULE_TO_BODY;

        List<String> keys = tool.bodyKeys();
        for (int i = 0; i < keys.size(); i++) {
            y = canvas.addWrappedAt(tool.body(keys.get(i)), innerX, innerW, y, CardCanvas.COLOUR_DESC);
            if (i < keys.size() - 1) {
                y += CardCanvas.PARA_GAP;
            }
        }

        int bottom = y + CardCanvas.CARD_PAD;
        canvas.addCard(top, bottom - top);
        return bottom;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return canvas.scroll(scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        canvas.renderPanel(g);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        canvas.render(g, this.width);

        // The clip, after the canvas pass and clipped to the content column — see the hub for why.
        int drawY = canvas.screenY(clipY);
        if (drawY + clipH >= canvas.viewportTop() && drawY <= canvas.viewportBottom()) {
            // Two px either side of the column so the clip's own 1px frame is not clipped off.
            g.enableScissor(canvas.colX() - 2, canvas.viewportTop(),
                    canvas.colX() + canvas.colW() + 2, canvas.viewportBottom());
            AnimatedSheet.draw(g, tool, canvas.colX(), drawY, canvas.colW(), clipH);
            g.renderOutline(canvas.colX() - 1, drawY - 1, canvas.colW() + 2, clipH + 2, COLOUR_CLIP_EDGE);
            g.disableScissor();
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
