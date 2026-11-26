package games.brennan.dungeontrain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * A compact perk button for the villager trade menu: an enlarged symbol and a small count of the
 * charges left, with "{label} (N)" on hover (also its narration). Draws nothing, inactive, while
 * {@code remaining} is negative (the server sent no status: perk inactive); greyed out with no
 * charges left or when {@code usable} says a press would change nothing. The server re-checks every
 * press.
 *
 * <p>With an {@code after} button it sits just right of that one whenever that one is drawn, and in
 * its place when it is not, so Restock and Reroll line up whichever perks are active.</p>
 */
final class TradeChargeButton extends Button {

    /** The symbols come from the small fallback font, so they are drawn enlarged... */
    private static final float ICON_SCALE = 2.0F;
    /** ...and the count shrunk, so the symbol reads first. */
    private static final float COUNT_SCALE = 0.75F;
    private static final int ICON_COUNT_GAP = 1;
    /** The fallback glyph sits low in its line and the shrunk count high; these centre both. */
    private static final float ICON_Y_NUDGE = -2.0F;
    private static final float COUNT_Y_NUDGE = 1.0F;
    static final int HEIGHT = 11;
    private static final int PADDING_X = 3;
    static final int GAP = 3;

    private final String icon;
    private final String labelKey;
    private final IntSupplier remaining;
    private final BooleanSupplier usable;
    private final TradeChargeButton after;
    private final int homeX;
    private int shownLeft = -1;

    TradeChargeButton(int x, int y, String icon, String labelKey, IntSupplier remaining, BooleanSupplier usable,
                      Runnable press, TradeChargeButton after) {
        super(x, y, 0, HEIGHT, Component.empty(), b -> press.run(), DEFAULT_NARRATION);
        this.icon = icon;
        this.labelKey = labelKey;
        this.remaining = remaining;
        this.usable = usable;
        this.after = after;
        this.homeX = x;
        this.active = false;
    }

    /** Whether the server has sent a status, so the button is drawn. */
    boolean shown() {
        return shownLeft >= 0;
    }

    /**
     * Refreshed every frame. The widget stays {@code visible} (vanilla would stop calling this
     * otherwise) and simply draws nothing, inactive, until the server's status arrives.
     */
    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = remaining.getAsInt();
        if (after != null) setX(after.shown() ? after.getX() + after.getWidth() + GAP : homeX);
        if (left < 0) {
            active = false;
            shownLeft = -1;
            return;
        }
        active = left > 0 && usable.getAsBoolean();
        if (left != shownLeft) {
            shownLeft = left;
            setMessage(Component.translatable(labelKey, left));
            setTooltip(Tooltip.create(getMessage()));
        }
        Font font = Minecraft.getInstance().font;
        setWidth(Math.round(iconWidth(font) + ICON_COUNT_GAP + countWidth(font)) + 2 * PADDING_X);
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
    }

    /** Draws the enlarged symbol then the smaller count in place of the label text. */
    @Override
    public void renderString(GuiGraphics graphics, Font font, int color) {
        float iconX = getX() + PADDING_X;
        drawScaled(graphics, font, icon, iconX, ICON_Y_NUDGE, ICON_SCALE, color);
        drawScaled(graphics, font, String.valueOf(shownLeft), iconX + iconWidth(font) + ICON_COUNT_GAP, COUNT_Y_NUDGE, COUNT_SCALE, color);
    }

    private void drawScaled(GuiGraphics graphics, Font font, String text, float x, float yNudge, float scale, int color) {
        float y = getY() + (getHeight() - font.lineHeight * scale) / 2.0F + scale / 2.0F + yNudge;
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(font, text, 0, 0, color);
        graphics.pose().popPose();
    }

    private float iconWidth(Font font) {
        return font.width(icon) * ICON_SCALE;
    }

    private float countWidth(Font font) {
        return font.width(String.valueOf(shownLeft)) * COUNT_SCALE;
    }
}
