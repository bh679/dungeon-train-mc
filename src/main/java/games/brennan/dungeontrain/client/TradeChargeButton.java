package games.brennan.dungeontrain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * A compact perk button for the villager trade menu: an enlarged symbol and a small count of the
 * charges left, then (when it has a {@code price}) " · M" and an emerald, or "FREE", with a tooltip
 * built from the charges left and the price (also its narration). Draws nothing, inactive, while
 * {@code remaining} is negative (the server sent no status: perk inactive); greyed out with no
 * charges left or when {@code usable} says a press would change nothing. The server re-checks every
 * press.
 *
 * <p>With an {@code after} button it sits just right of that one whenever that one is drawn, and at
 * its own {@code homeX} when it is not, so Restock and Reroll line up whichever perks are active.</p>
 */
final class TradeChargeButton extends Button {

    /**
     * The symbols come from the small fallback font, so they are drawn enlarged (each button sets its
     * own scale: the die's glyph is already tall)...
     */
    static final float DEFAULT_ICON_SCALE = 2.0F;
    /** ...and the count shrunk, so the symbol reads first. */
    private static final float COUNT_SCALE = 0.75F;
    private static final int ICON_COUNT_GAP = 1;
    /** The fallback glyph sits low in its line and the shrunk count high; these centre both (per unit of enlargement). */
    private static final float ICON_Y_NUDGE_PER_SCALE = -2.0F;
    private static final float COUNT_Y_NUDGE = 1.0F;
    static final int HEIGHT = 11;
    private static final int PADDING_X = 2;
    static final int GAP = 3;
    private static final String SEPARATOR = "\u00b7";
    private static final float EMERALD_SCALE = 0.4375F;
    private static final int EMERALD_SIZE = 7;
    private static final ItemStack EMERALD = new ItemStack(Items.EMERALD);

    private final String icon;
    private final float iconScale;
    private final BiFunction<Integer, Integer, Component> tooltip;
    private final IntSupplier remaining;
    private final IntSupplier price;
    private final Component freeLabel;
    private final BooleanSupplier usable;
    private final TradeChargeButton after;
    private final IntSupplier homeX;
    private int shownLeft = -1;
    private int shownPrice = -1;

    /**
     * {@code price} returns the next use's emerald price (0 = free) or -1 for a button with no price;
     * {@code tooltip} gets the charges left and that price. {@code homeX} is where it sits when there is
     * no {@code after} button showing; read every frame, so it can move.
     */
    TradeChargeButton(IntSupplier homeX, int y, String icon, float iconScale, BiFunction<Integer, Integer, Component> tooltip, IntSupplier remaining,
                      IntSupplier price, Component freeLabel, BooleanSupplier usable, Runnable press, TradeChargeButton after) {
        super(homeX.getAsInt(), y, 0, HEIGHT, Component.empty(), b -> press.run(), DEFAULT_NARRATION);
        this.icon = icon;
        this.iconScale = iconScale;
        this.tooltip = tooltip;
        this.remaining = remaining;
        this.price = price;
        this.freeLabel = freeLabel;
        this.usable = usable;
        this.after = after;
        this.homeX = homeX;
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
        setX(after != null && after.shown() ? after.getX() + after.getWidth() + GAP : homeX.getAsInt());
        if (left < 0) {
            active = false;
            shownLeft = -1;
            return;
        }
        active = left > 0 && usable.getAsBoolean();
        int cost = left > 0 ? price.getAsInt() : -1;
        if (left != shownLeft || cost != shownPrice) {
            shownLeft = left;
            shownPrice = cost;
            setMessage(tooltip.apply(left, cost));
            setTooltip(Tooltip.create(getMessage()));
        }
        Font font = Minecraft.getInstance().font;
        setWidth(Math.round(iconWidth(font) + ICON_COUNT_GAP + countWidth(font) + priceWidth(font)) + 2 * PADDING_X);
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
    }

    /** Draws the enlarged symbol then the smaller count in place of the label text. */
    @Override
    public void renderString(GuiGraphics graphics, Font font, int color) {
        float iconX = getX() + PADDING_X;
        drawScaled(graphics, font, icon, iconX, ICON_Y_NUDGE_PER_SCALE * (iconScale - 1.0F), iconScale, color);
        float countX = iconX + iconWidth(font) + ICON_COUNT_GAP;
        drawScaled(graphics, font, String.valueOf(shownLeft), countX, COUNT_Y_NUDGE, COUNT_SCALE, color);
        if (shownPrice < 0) return;
        float priceX = countX + countWidth(font);
        String text = priceText();
        drawScaled(graphics, font, text, priceX, COUNT_Y_NUDGE, COUNT_SCALE, color);
        if (shownPrice == 0) return;
        float emeraldX = priceX + font.width(text) * COUNT_SCALE;
        float emeraldY = getY() + (getHeight() - EMERALD_SIZE) / 2.0F;
        graphics.pose().pushPose();
        graphics.pose().translate(emeraldX, emeraldY, 0.0F);
        graphics.pose().scale(EMERALD_SCALE, EMERALD_SCALE, 1.0F);
        graphics.renderFakeItem(EMERALD, 0, 0);
        graphics.pose().popPose();
    }

    /** " · 8" for a paid use, " · FREE" for a free one. */
    private String priceText() {
        return SEPARATOR + (shownPrice == 0 ? freeLabel.getString() : String.valueOf(shownPrice));
    }

    private float priceWidth(Font font) {
        if (shownPrice < 0) return 0.0F;
        return font.width(priceText()) * COUNT_SCALE + (shownPrice > 0 ? EMERALD_SIZE : 0);
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
        return font.width(icon) * iconScale;
    }

    private float countWidth(Font font) {
        return font.width(String.valueOf(shownLeft)) * COUNT_SCALE;
    }
}
