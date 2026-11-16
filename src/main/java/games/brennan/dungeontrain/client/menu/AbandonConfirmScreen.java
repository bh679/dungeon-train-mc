package games.brennan.dungeontrain.client.menu;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * "Abandon this run?" — the step between the pause menu's red Abandon button and the kill it sends.
 *
 * <p>The button says <i>abandon</i>, which reads like leaving; what actually happens is that the
 * character dies and the run is over. One misclick used to be enough. So the screen spells out the
 * part the label doesn't: <b>you will die</b>. It then
 * points at the way to stop without dying (a bed the train placed saves the run and quits), and
 * shows everything the player is carrying, backpack included, so they see what they'd be giving
 * up and are reminded the Ender Chest keeps things for next time.</p>
 *
 * <p>One centred row of two buttons — the destructive one on the left, the way out on its right,
 * the order vanilla's own confirm screens use — and Escape falling back to the option that changes
 * nothing. Hovering the destructive button washes the whole screen red, so the choice reads as the
 * death it is. What "continue" means is the caller's: {@code proceed} runs only once the player has
 * said yes. On a screen too short for it all, the backpack goes first, then the whole grid; the
 * buttons always fit.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class AbandonConfirmScreen extends Screen {

    private static final int BUTTON_WIDTH = 150;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 8;
    private static final int LINE_H = 10;
    private static final int TEXT_MARGIN = 20;
    private static final int GRID_GAP = 6;
    private static final int BUTTON_GAP = 10;
    private static final int EDGE = 4;

    private static final int WHITE = 0xFFFFFF;
    private static final int RED = 0xFF6060;
    private static final int GREY = 0xA0A0A0;
    private static final int GOLD = 0xFFD860;

    /** Red wash over the world at full hover, and how long it takes to fade in or out. */
    private static final int WASH_RGB = 0xB00000;
    private static final int WASH_MAX_ALPHA = 0x90;
    private static final float WASH_FADE_MS = 400F;

    private static final Component LINE_1 = Component.translatable("gui.dungeontrain.abandon_confirm.line1");
    private static final Component BED_HINT = Component.translatable("gui.dungeontrain.abandon_confirm.bed_hint");
    private static final Component INVENTORY = Component.translatable("gui.dungeontrain.abandon_confirm.inventory");

    /** One wrapped line of the text block, in its colour, at its y. */
    private record Line(FormattedCharSequence text, int color, int y) {}

    private final Screen lastScreen;
    private final Runnable proceed;
    private final List<Line> lines = new ArrayList<>();
    private AbandonInventoryGrid grid;
    private Button stepOff;
    private float wash;
    private long lastWashMs = -1;

    /**
     * @param lastScreen the pause menu to fall back to — cancelling must leave the run exactly as it was
     * @param proceed    the abandon itself, run once the player has confirmed the death
     */
    public AbandonConfirmScreen(Screen lastScreen, Runnable proceed) {
        super(Component.translatable("gui.dungeontrain.abandon_confirm.title"));
        this.lastScreen = lastScreen;
        this.proceed = proceed;
    }

    @Override
    protected void init() {
        int wrap = Math.max(100, this.width - 2 * TEXT_MARGIN);
        List<Line> warning = wrapAll(wrap, this.title, WHITE, LINE_1, RED, BED_HINT, GOLD);
        List<Line> label = wrapAll(wrap, INVENTORY, GREY);

        int room = this.height - 2 * EDGE;
        int fixed = (warning.size() + 1) * LINE_H + BUTTON_GAP + BUTTON_HEIGHT;   // +1: gap before the hint block's end
        grid = fittingGrid(room - fixed - label.size() * LINE_H - GRID_GAP);
        int gridH = grid == null ? 0 : label.size() * LINE_H + GRID_GAP + grid.height();
        int y = Math.max(EDGE, (this.height - fixed - gridH) / 2);

        lines.clear();
        y = addLines(warning, y) + LINE_H;
        if (grid != null) {
            y = addLines(label, y) + GRID_GAP;
            grid.place((this.width - grid.width()) / 2, y);
            y += grid.height();
        }
        addButtons(y + BUTTON_GAP);
    }

    /** The grid with the backpack if it fits, without it if only that fits, else none. */
    private AbandonInventoryGrid fittingGrid(int maxHeight) {
        var player = Minecraft.getInstance().player;
        AbandonInventoryGrid full = AbandonInventoryGrid.of(player, true);
        if (full != null && full.height() <= maxHeight && full.width() <= this.width - 2 * EDGE) return full;
        AbandonInventoryGrid bare = AbandonInventoryGrid.of(player, false);
        if (bare != null && bare.height() <= maxHeight && bare.width() <= this.width - 2 * EDGE) return bare;
        return null;
    }

    private void addButtons(int y) {
        int x = (this.width - (BUTTON_WIDTH * 2 + GAP)) / 2;

        // The same red the Abandon button carries on the pause menu — the colour is the warning.
        stepOff = this.addRenderableWidget(new ColorTintedButton(x, y, BUTTON_WIDTH, BUTTON_HEIGHT,
                Component.translatable("gui.dungeontrain.abandon_confirm.yes"),
                1.0F, 0.30F, 0.30F,
                b -> proceedNow()));

        this.addRenderableWidget(Button.builder(
                        Component.translatable("gui.dungeontrain.abandon_confirm.back"),
                        b -> this.onClose())
                .bounds(x + BUTTON_WIDTH + GAP, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    /** {@code parts} alternates Component, colour. Lines come back with y unset (0). */
    private List<Line> wrapAll(int wrap, Object... parts) {
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < parts.length; i += 2) {
            int color = (Integer) parts[i + 1];
            for (FormattedCharSequence s : this.font.split((Component) parts[i], wrap)) {
                out.add(new Line(s, color, 0));
            }
        }
        return out;
    }

    private int addLines(List<Line> block, int y) {
        for (Line l : block) {
            lines.add(new Line(l.text(), l.color(), y));
            y += LINE_H;
        }
        return y;
    }

    /** The world behind the screen, washed red while the player hovers "Step off and die". */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        updateWash();
        int alpha = Math.round(wash * WASH_MAX_ALPHA);
        if (alpha > 0) g.fill(0, 0, this.width, this.height, (alpha << 24) | WASH_RGB);
    }

    /** Eases {@link #wash} toward 1 while the step-off button is hovered, back to 0 otherwise. */
    private void updateWash() {
        long now = Util.getMillis();
        float step = lastWashMs < 0 ? 0F : (now - lastWashMs) / WASH_FADE_MS;
        lastWashMs = now;
        boolean hot = stepOff != null && stepOff.isHovered();
        wash = hot ? Math.min(1F, wash + step) : Math.max(0F, wash - step);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        for (Line l : lines) g.drawCenteredString(this.font, l.text(), this.width / 2, l.y(), l.color());
        if (grid != null) {
            grid.render(g, this.font);
            grid.renderTooltip(g, this.font, mouseX, mouseY);
        }
    }

    /** Escape and Go back land in the same place: the pause menu, run untouched. */
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(lastScreen);
    }

    private void proceedNow() {
        Minecraft.getInstance().setScreen(null);
        proceed.run();
    }
}
