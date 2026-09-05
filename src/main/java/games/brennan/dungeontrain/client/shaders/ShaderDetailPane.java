package games.brennan.dungeontrain.client.shaders;

import games.brennan.dungeontrain.client.ui.ListScrollbar;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the Shaders page says about the selected pack below its name, in one scrolling window.
 *
 * <h2>Why a window rather than laid-out lines</h2>
 * <p>The first version drew each part at a fixed offset — version line, performance, its detail,
 * then the status. That makes the pane's height a function of how long a translated sentence is,
 * and the status line was positioned for the short case, so a three-line detail drew straight
 * through it. Making the detail a single horizontally-scrolling line stopped the collision but hid
 * most of the sentence.</p>
 *
 * <p>So the whole block scrolls instead. Nothing below the name has a fixed position, the text can
 * be any length in any language, and what does not fit is reachable rather than lost.</p>
 */
public final class ShaderDetailPane extends AbstractWidget {

    /** One paragraph: its text and the colour that carries its meaning. */
    public record Line(Component text, int colour) {}

    private static final int PAD = 4;
    /** Blank space between paragraphs, so the status does not read as part of the detail above it. */
    private static final int PARAGRAPH_GAP = 4;

    private final Font font;
    private final ListScrollbar scrollbar = new ListScrollbar();

    private List<Line> lines = List.of();
    /** {@link #lines} wrapped to the current width, flattened, with a colour per drawn row. */
    private final List<FormattedCharSequence> wrapped = new ArrayList<>();
    private final List<Integer> colours = new ArrayList<>();
    private final List<Boolean> gapBefore = new ArrayList<>();
    private int scroll;

    public ShaderDetailPane(Font font, int x, int y, int width, int height) {
        super(x, y, width, height, Component.translatable("gui.dungeontrain.shaders.details"));
        this.font = font;
    }

    /**
     * Replace the text. The scroll position is kept — the content is rebuilt every frame so the
     * download status stays live, and resetting it there would make the pane impossible to scroll.
     * Callers reset it themselves when the selection changes ({@link #resetScroll()}).
     */
    public void setLines(List<Line> newLines) {
        if (newLines.equals(lines)) {
            return;
        }
        lines = List.copyOf(newLines);
        relayout();
    }

    public void resetScroll() {
        scroll = 0;
    }

    private void relayout() {
        wrapped.clear();
        colours.clear();
        gapBefore.clear();
        int textWidth = width - PAD * 2 - ListScrollbar.WIDTH - 1;
        for (Line line : lines) {
            boolean first = true;
            for (FormattedCharSequence row : font.split(line.text(), Math.max(1, textWidth))) {
                wrapped.add(row);
                colours.add(line.colour());
                gapBefore.add(first && !wrapped.isEmpty() && wrapped.size() > 1);
                first = false;
            }
        }
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    @Override
    public void setWidth(int width) {
        super.setWidth(width);
        relayout();
    }

    private int totalHeight() {
        int h = PAD * 2;
        for (int i = 0; i < wrapped.size(); i++) {
            h += font.lineHeight + (gapBefore.get(i) ? PARAGRAPH_GAP : 0);
        }
        return h;
    }

    private int maxScroll() {
        return Math.max(0, totalHeight() - height);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.enableScissor(getX(), getY(), getX() + width, getY() + height);
        int y = getY() + PAD - scroll;
        for (int i = 0; i < wrapped.size(); i++) {
            if (gapBefore.get(i)) {
                y += PARAGRAPH_GAP;
            }
            if (y + font.lineHeight >= getY() && y <= getY() + height) {
                g.drawString(font, wrapped.get(i), getX() + PAD, y, colours.get(i));
            }
            y += font.lineHeight;
        }
        g.disableScissor();
        scrollbar.render(g, getX(), getY(), width, height, totalHeight(), scroll, maxScroll());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || button != 0 || !isMouseOver(mouseX, mouseY) || maxScroll() == 0) {
            return false;
        }
        if (scrollbar.isOverTrack(mouseX, getX(), width)) {
            scrollbar.begin();
            scroll = scrollbar.scrollFor(mouseY, getY(), height, totalHeight(), maxScroll());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!scrollbar.isDragging()) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        scroll = scrollbar.scrollFor(mouseY, getY(), height, totalHeight(), maxScroll());
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        scrollbar.end();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!visible || !isMouseOver(mouseX, mouseY) || maxScroll() == 0) {
            return false;
        }
        scroll = Mth.clamp(scroll - (int) (scrollY * font.lineHeight * 3), 0, maxScroll());
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        StringBuilder all = new StringBuilder();
        for (Line line : lines) {
            all.append(line.text().getString()).append(' ');
        }
        output.add(NarratedElementType.TITLE, Component.literal(all.toString().trim()));
    }
}
