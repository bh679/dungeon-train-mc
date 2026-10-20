package games.brennan.dungeontrain.client.support.currency;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;

/**
 * The small currency button at the top right of the Contribute screens ([flag] AUD ▾) and the list
 * it opens: one row per currency, flag and code only. Picking one re-lays the host out so every
 * amount shows converted.
 *
 * <p>Driven by the host like {@code client/videos/UploaderDropdown}: the button is an ordinary
 * widget, but the list is drawn after everything else at tooltip height, and the host gives it first
 * refusal on clicks, scrolls and Esc, so it sits over the tiers it covers.</p>
 *
 * <p>Hidden while AUD is the only choice (no rates from the relay yet): a list of one is noise.</p>
 */
public final class CurrencyPicker {

    private static final int BUTTON_H = 14;
    private static final int ROW_H = 12;
    private static final int FLAG_W = 12;
    private static final int FLAG_H = 8;
    private static final int PAD = 3;
    private static final int MIN_ROWS = 3;
    private static final float Z = 400.0F;

    private static final int FACE = 0xFF4A4A4A;
    private static final int FACE_HOVER = 0xFF5E5E5E;
    private static final int EDGE = 0xFF000000;
    private static final int LIGHT = 0xFF777777;
    private static final int SHADE = 0xFF2A2A2A;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int LIST_BG = 0xF0101010;
    private static final int LIST_BORDER = 0xFF5A5A5A;
    private static final int ROW_HOVER = 0x40FFFFFF;
    private static final int ROW_SELECTED = 0xFF3A3A3A;

    private final String surface;
    private final Runnable relayout;

    private List<DisplayCurrency> rows = List.of();
    private CurrencyButton button;
    private boolean open;
    private int listX, listY, listW, visibleRows, scroll;

    /** {@code relayout} is the host's {@code rebuildWidgets}, run after a pick so amounts redraw. */
    public CurrencyPicker(String surface, Runnable relayout) {
        this.surface = surface;
        this.relayout = relayout;
    }

    /**
     * Place the button with its right edge at {@code rightX} and its top at {@code y}, adding it
     * through {@code add}; the list opens under it, as tall as {@code screenH} allows. Also asks the
     * relay, once, for a currency picked on another install.
     */
    public void layout(Font font, int rightX, int y, int screenH, Consumer<AbstractWidget> add) {
        CurrencyChoice.adoptFromRelay(relayout);
        rows = CurrencyChoice.available();
        button = null;
        if (rows.size() < 2) {
            open = false;
            return;
        }
        String widest = "MMM ▾";
        int w = PAD + FLAG_W + PAD + font.width(widest) + PAD;
        button = new CurrencyButton(rightX - w, y, w, font);
        button.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.currency.tooltip")));
        add.accept(button);

        listW = PAD + FLAG_W + PAD + font.width("MMM") + PAD * 2;
        listX = rightX - listW;
        listY = y + BUTTON_H + 1;
        visibleRows = Math.min(rows.size(), Math.max(MIN_ROWS, (screenH - listY - 4) / ROW_H));
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    private int maxScroll() {
        return Math.max(0, rows.size() - visibleRows);
    }

    private int listH() {
        return visibleRows * ROW_H + 2;
    }

    private boolean overList(double mx, double my) {
        return open && mx >= listX && mx < listX + listW && my >= listY && my < listY + listH();
    }

    /**
     * The button, or {@code null} while hidden. For a host that adds it with {@code addWidget} and
     * draws it itself, above content it clips and scrolls (the Community Rewards shelf).
     */
    public AbstractWidget button() {
        return button;
    }

    /** Draw the open list over everything; call last in the host's render. */
    public void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        if (!open || button == null) return;
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, Z);
        try {
            int h = listH();
            g.fill(listX, listY, listX + listW, listY + h, LIST_BG);
            g.renderOutline(listX, listY, listW, h, LIST_BORDER);
            DisplayCurrency current = CurrencyChoice.current();
            for (int i = 0; i < visibleRows; i++) {
                int idx = scroll + i;
                if (idx >= rows.size()) break;
                DisplayCurrency c = rows.get(idx);
                int rowY = listY + 1 + i * ROW_H;
                if (c == current) g.fill(listX + 1, rowY, listX + listW - 1, rowY + ROW_H, ROW_SELECTED);
                if (mouseX >= listX && mouseX < listX + listW && mouseY >= rowY && mouseY < rowY + ROW_H) {
                    g.fill(listX + 1, rowY, listX + listW - 1, rowY + ROW_H, ROW_HOVER);
                }
                drawFlagAndCode(g, font, c, c.code(), listX + PAD, rowY + (ROW_H - FLAG_H) / 2, rowY + 2);
            }
            if (scroll + visibleRows < rows.size()) g.fill(listX + listW / 2 - 3, listY + h - 2, listX + listW / 2 + 3, listY + h - 1, TEXT);
            if (scroll > 0) g.fill(listX + listW / 2 - 3, listY + 1, listX + listW / 2 + 3, listY + 2, TEXT);
        } finally {
            g.pose().popPose();
        }
    }

    /** First refusal on a click: picks a row, or closes the list on a click anywhere else. */
    public boolean mouseClicked(double mx, double my, int mouseButton) {
        if (!open) return false;
        if (overList(mx, my)) {
            int idx = scroll + (int) ((my - listY - 1) / ROW_H);
            if (mouseButton == 0 && idx >= 0 && idx < rows.size()) {
                open = false;
                CurrencyChoice.pick(rows.get(idx), surface);
                relayout.run();
            }
            return true;
        }
        open = false;
        // A click on the button itself closes the list and is consumed here, so it doesn't reopen it.
        return button != null && button.isMouseOver(mx, my);
    }

    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (!overList(mx, my)) return false;
        scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll());
        return true;
    }

    /** Esc closes an open list instead of the screen. */
    public boolean keyPressed(int keyCode) {
        if (open && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            open = false;
            return true;
        }
        return false;
    }

    private static void drawFlagAndCode(GuiGraphics g, Font font, DisplayCurrency c, String text,
                                        int x, int flagY, int textY) {
        g.blitSprite(c.flag(), x, flagY, FLAG_W, FLAG_H);
        g.renderOutline(x - 1, flagY - 1, FLAG_W + 2, FLAG_H + 2, 0x80000000);
        g.drawString(font, text, x + FLAG_W + PAD, textY, TEXT, false);
    }

    /** The [flag] AUD ▾ button, bevelled like the design's small grey chip. */
    private final class CurrencyButton extends AbstractButton {
        private final Font font;

        CurrencyButton(int x, int y, int w, Font font) {
            super(x, y, w, BUTTON_H, Component.empty());
            this.font = font;
        }

        @Override
        public void onPress() {
            open = !open;
            if (open) UiAnalytics.click(surface, UiAnalytics.TARGET_CURRENCY);
        }

        @Override
        public Component getMessage() {
            return Component.literal(CurrencyChoice.current().code());
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            g.fill(x, y, x + w, y + h, EDGE);
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, isHoveredOrFocused() || open ? FACE_HOVER : FACE);
            g.fill(x + 1, y + 1, x + w - 1, y + 2, LIGHT);
            g.fill(x + 1, y + 1, x + 2, y + h - 1, LIGHT);
            g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, SHADE);
            g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, SHADE);
            DisplayCurrency c = CurrencyChoice.current();
            drawFlagAndCode(g, font, c, c.code() + " ▾", x + PAD, y + (h - FLAG_H) / 2, y + (h - font.lineHeight) / 2 + 1);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }
}
