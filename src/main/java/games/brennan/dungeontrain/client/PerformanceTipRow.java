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
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.List;

/**
 * One line of the Options screen's Performance tab: a {@link LagTips.Tip}'s text on the left and its
 * button (or grey hint) on the right, wrapping to two lines when the text needs it — the same row the death screen's bug-report card draws, in
 * vanilla widgets.
 *
 * <p>A single widget rather than a pair because {@code OptionsList} pairs at a fixed 150px split, and
 * a tip's text is wider than that. The inner button is positioned and drawn here, and clicks are
 * handed to it. Also used, with no tip, for the tab's plain caption lines.</p>
 */
@OnlyIn(Dist.CLIENT)
final class PerformanceTipRow extends AbstractWidget {

    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int CAPTION_COLOR = 0xFFE0B56A;
    /** A tip whose setting is already fine here — listed so the setting can be found, not flagged. */
    private static final int DIM_COLOR = 0xFF9A9A9A;
    private static final int WARN_COLOR = 0xFFE9B04F;
    private static final int HINT_COLOR = 0xFF948A70;
    private static final int BUTTON_PADDING = 12;
    private static final int GAP = 6;
    /** Baseline-to-baseline step when a tip wraps onto two lines. */
    private static final int LINE_STEP = 10;

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
        if (font.split(text, textWidth()).size() > 2) {
            setTooltip(Tooltip.create(text));
        }
    }

    /**
     * A tip row. A tip that does not apply to this setup is drawn dimmed. {@code afterAction} runs
     * after the tip's own action (e.g. to rebuild the screen).
     */
    static PerformanceTipRow tip(Font font, int width, int height, LagTips.Tip tip, Runnable afterAction) {
        Button button = null;
        if (tip.button() != null && tip.action() != null) {
            button = button(font, height, tip.button(), tip.action(), afterAction);
        }
        return new PerformanceTipRow(font, width, height, Component.literal("• ").append(tip.text()),
                tip.applies() ? TEXT_COLOR : DIM_COLOR, button, button == null ? tip.hint() : null);
    }

    /** A line of text with one button — the "releases behind" line and its See changes. */
    static PerformanceTipRow action(Font font, int width, int height, Component text, Component label,
                                    Runnable action) {
        return new PerformanceTipRow(font, width, height, text, WARN_COLOR,
                button(font, height, label, action, () -> { }), null);
    }

    private static Button button(Font font, int height, Component label, Runnable action, Runnable after) {
        return Button.builder(label, b -> {
                    action.run();
                    after.run();
                })
                .size(font.width(label) + BUTTON_PADDING, height)
                .build();
    }

    /** A gold caption line with nothing to click. */
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
        // Wraps onto a second line inside the 20px row (two 9px lines fit); a third is trimmed, and the
        // tooltip set in the constructor carries the whole text.
        List<FormattedCharSequence> lines = font.split(text, textWidth());
        if (lines.size() == 1) {
            g.drawString(font, lines.get(0), getX(), textY, color, true);
            return;
        }
        int top = getY() + (getHeight() - (LINE_STEP + font.lineHeight)) / 2 + 1;
        g.drawString(font, lines.get(0), getX(), top, color, true);
        if (lines.size() == 2) {
            g.drawString(font, lines.get(1), getX(), top + LINE_STEP, color, true);
        } else {
            String rest = text.getString().substring(lineLength(lines.get(0)));
            String shown = font.plainSubstrByWidth(rest.stripLeading(), textWidth() - font.width("...")) + "...";
            g.drawString(font, shown, getX(), top + LINE_STEP, color, true);
        }
    }

    private static int lineLength(FormattedCharSequence line) {
        StringBuilder sb = new StringBuilder();
        line.accept((index, style, codePoint) -> {
            sb.appendCodePoint(codePoint);
            return true;
        });
        return sb.length();
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
