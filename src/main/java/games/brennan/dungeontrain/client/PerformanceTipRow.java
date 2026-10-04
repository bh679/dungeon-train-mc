package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.client.bugresponse.LagTips;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/**
 * One line of the Options screen's Performance tab: a {@link LagTips.Tip}'s text on the left and its
 * button (or grey hint) on the right — the same row the death screen's bug-report card draws, in
 * vanilla widgets.
 *
 * <p>A single widget rather than a pair because {@code OptionsList} pairs at a fixed 150px split, and
 * a tip's text is wider than that. The inner button is positioned and drawn here, and clicks are
 * handed to it. Also used, with no tip, for the tab's plain caption lines.</p>
 */
@OnlyIn(Dist.CLIENT)
final class PerformanceTipRow extends AbstractWidget {

    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int CAPTION_COLOR = 0xFFAAAAAA;
    private static final int HINT_COLOR = 0xFF948A70;
    private static final int BUTTON_PADDING = 12;
    private static final int GAP = 6;

    private final Font font;
    private final Component text;
    private final int color;
    @Nullable private final Button button;
    @Nullable private final Component hint;

    private PerformanceTipRow(Font font, int width, int height, Component text, int color,
                              @Nullable Button button, @Nullable Component hint) {
        super(0, 0, width, height, text);
        this.font = font;
        this.text = text;
        this.color = color;
        this.button = button;
        this.hint = hint;
        if (font.width(text) > textWidth()) {
            setTooltip(Tooltip.create(text));
        }
    }

    /** A tip row. {@code afterAction} runs after the tip's own action (e.g. to rebuild the screen). */
    static PerformanceTipRow tip(Font font, int width, int height, LagTips.Tip tip, Runnable afterAction) {
        Button button = null;
        if (tip.button() != null && tip.action() != null) {
            Runnable action = tip.action();
            button = Button.builder(tip.button(), b -> {
                        action.run();
                        afterAction.run();
                    })
                    .size(font.width(tip.button()) + BUTTON_PADDING, height)
                    .build();
        }
        return new PerformanceTipRow(font, width, height, Component.literal("• ").append(tip.text()),
                TEXT_COLOR, button, button == null ? tip.hint() : null);
    }

    /** A line of grey text with nothing to click. */
    static PerformanceTipRow caption(Font font, int width, int height, Component text) {
        PerformanceTipRow row = new PerformanceTipRow(font, width, height, text, CAPTION_COLOR, null, null);
        row.active = false;
        return row;
    }

    /** Room left for the text once the button or hint has taken its share on the right. */
    private int textWidth() {
        if (button != null) return getWidth() - button.getWidth() - GAP;
        if (hint != null) return getWidth() - font.width(hint) - GAP;
        return getWidth();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int textY = getY() + (getHeight() - font.lineHeight) / 2 + 1;
        int right = getX() + getWidth();
        if (button != null) {
            button.setPosition(right - button.getWidth(), getY());
            button.render(g, mouseX, mouseY, partialTick);
        } else if (hint != null) {
            g.drawString(font, hint, right - font.width(hint), textY, HINT_COLOR, false);
        }
        String full = text.getString();
        String shown = font.plainSubstrByWidth(full, textWidth());
        if (shown.length() < full.length()) {
            shown = font.plainSubstrByWidth(full, textWidth() - font.width("...")) + "...";
        }
        g.drawString(font, shown, getX(), textY, color, true);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int buttonId) {
        return button != null && button.mouseClicked(mouseX, mouseY, buttonId);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int buttonId) {
        return button != null && button.mouseReleased(mouseX, mouseY, buttonId);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return button != null && button.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, text);
        if (button != null) output.add(NarratedElementType.USAGE, button.getMessage());
        else if (hint != null) output.add(NarratedElementType.HINT, hint);
    }
}
