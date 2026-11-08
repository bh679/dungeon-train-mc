package games.brennan.dungeontrain.client.perks;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The details bar's Unlock, a tab standing on the bar's top edge, centred. Two parts tell level from
 * cost without more words: on the gold left, the level this unlock buys in a round badge, then
 * "Unlock"; on the dark right, the Advancement Points coin and the cost. Greyed when it can't be
 * pressed. Four looks ({@link Look}): {@link Look#READY} filled gold, the selected perk can be
 * unlocked now; {@link Look#UNFILLED} grey but pressable, it can be unlocked though its points aren't
 * filled yet; {@link Look#AVAILABLE} a gold outline, a hovered perk that could be unlocked once
 * selected; {@link Look#BLOCKED} grey, it can't be unlocked (the bar says why for the selected perk).
 * While a perk's points aren't filled the whole tab draws small, whichever look it has.
 *
 * <p>In play, where unlock points are not spent, the same tab is Activate ({@link #activate}): the badge
 * is the point it puts in, the cost is in Cosmic Apples.</p>
 */
@OnlyIn(Dist.CLIENT)
final class UnlockTab extends Button {

    static final int H = 18;
    private static final int PAD = 6, BADGE_R = 6, BADGE_GAP = 5, ICON = 8, ICON_GAP = 3, COST_PAD = 6;

    private static final int GOLD = 0xE4B95A, GOLD_HOVER = 0xF0CC70, EDGE = 0xFFE3A0, INK = 0x3A2A06;
    private static final int OFF = 0x2A3350, OFF_EDGE = 0x3A3F6A, OFF_TEXT = 0x8E94A8, OFF_TEXT_HOVER = 0xE3E6F0, OFF_COST = 0x1B1F36;
    private static final float SMALL_SCALE = 0.6F;

    enum Look { READY, UNFILLED, AVAILABLE, BLOCKED }

    private final int level;
    private final String cost;
    /** Paid in Cosmic Apples (Activate), not Advancement Points. */
    private final boolean apple;
    /** The width it draws at before {@link #scale}. */
    private final int fullW;
    private final float scale;
    private Look look = Look.READY;

    private UnlockTab(int x, int y, int w, int h, int fullW, float scale, Component label, int level, int cost, boolean apple,
                      OnPress onPress) {
        super(x, y, w, h, label, onPress, DEFAULT_NARRATION);
        this.level = level;
        this.cost = Integer.toString(cost);
        this.apple = apple;
        this.fullW = fullW;
        this.scale = scale;
    }

    /**
     * A tab for unlocking {@code level} at {@code cost}, centred on {@code centreX}, standing on {@code barTop};
     * {@code small} shrinks it, hit box and all.
     */
    static UnlockTab centred(Font font, int centreX, int barTop, Component label, int level, int cost, boolean small, OnPress onPress) {
        return centred(font, centreX, barTop, label, level, cost, small, false, onPress);
    }

    /** The tab as Activate: putting in point {@code point} for {@code cost} Cosmic Apples. */
    static UnlockTab activate(Font font, int centreX, int barTop, Component label, int point, int cost, OnPress onPress) {
        return centred(font, centreX, barTop, label, point, cost, false, true, onPress);
    }

    private static UnlockTab centred(Font font, int centreX, int barTop, Component label, int level, int cost, boolean small,
                                     boolean apple, OnPress onPress) {
        int fullW = leftWidth(font, label, level) + costWidth(font, Integer.toString(cost));
        float scale = small ? SMALL_SCALE : 1F;
        int w = (int) Math.ceil(fullW * scale), h = (int) Math.ceil(H * scale);
        return new UnlockTab(centreX - w / 2, barTop - h, w, h, fullW, scale, label, level, cost, apple, onPress);
    }

    /** Sets how it looks; {@link Look#READY} and {@link Look#UNFILLED} can be pressed. */
    void look(Look look) {
        this.look = look;
        this.active = look == Look.READY || look == Look.UNFILLED;
    }

    private static int leftWidth(Font font, Component label, int level) {
        return PAD + BADGE_R * 2 + BADGE_GAP + font.width(label) + PAD;
    }

    private static int costWidth(Font font, String cost) {
        return COST_PAD + ICON + ICON_GAP + font.width(cost) + COST_PAD;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Font font = net.minecraft.client.Minecraft.getInstance().font;
        int a = Math.round(alpha * 255);
        if (a <= 8) return;
        // drawn at full size from the origin, scaled down onto the hit box so its open bottom still meets the bar
        g.pose().pushPose();
        g.pose().translate(getX(), getY() + getHeight() - H * scale, 0);
        g.pose().scale(scale, scale, 1F);
        draw(g, font, a);
        g.pose().popPose();
    }

    private void draw(GuiGraphics g, Font font, int a) {
        boolean on = look == Look.READY || look == Look.AVAILABLE;
        boolean outline = look == Look.AVAILABLE;
        // grey, but a mouse on it lights the edge and the word: this one can be pressed
        boolean lift = look == Look.UNFILLED && isHoveredOrFocused();
        int x = 0, y = 0, w = fullW, split = x + leftWidth(font, getMessage(), level);
        // ready: filled gold; available: dark with a gold outline and gold words; unfilled and blocked: grey
        int body = outline ? OFF_COST : on ? (isHoveredOrFocused() ? GOLD_HOVER : GOLD) : OFF;
        int edge = outline || lift ? GOLD : on ? EDGE : OFF_EDGE;
        int ink = outline ? GOLD : on ? INK : lift ? OFF_TEXT_HOVER : OFF_TEXT;
        // the tab: rounded top corners, open at the bottom where it meets the bar
        g.fill(x + 1, y, x + w - 1, y + 1, argb(a, edge));
        g.fill(x, y + 1, x + 1, y + H, argb(a, edge));
        g.fill(x + w - 1, y + 1, x + w, y + H, argb(a, edge));
        g.fill(x + 1, y + 1, split, y + H, argb(a, body));
        g.fill(split, y + 1, x + w - 1, y + H, argb(a, on && !outline ? INK : OFF_COST));
        if (outline) g.fill(split, y + 1, split + 1, y + H, argb(a, GOLD)); // the line between word and cost
        // level badge, then the word
        double bx = x + PAD + BADGE_R, by = y + 1 + (H - 1) / 2.0;
        if (outline) SkyDraw.ring(g, bx, by, BADGE_R, 1.0, argb(a, GOLD));
        else SkyDraw.disc(g, bx, by, BADGE_R, argb(a, on ? INK : OFF_EDGE));
        String lv = Integer.toString(level);
        g.drawString(font, lv, (int) Math.round(bx - font.width(lv) / 2.0), (int) Math.round(by - 3.5), argb(a, on ? GOLD : OFF_TEXT), false);
        int tx = x + PAD + BADGE_R * 2 + BADGE_GAP, ty = y + (H - 8) / 2 + 1;
        g.drawString(font, getMessage(), tx, ty, argb(a, ink), false);
        // cost: the coin and the number, light on dark
        int cx = split + COST_PAD, iy = y + 1 + (H - 1 - ICON) / 2;
        if (apple) {
            g.pose().pushPose();
            g.pose().translate(cx, iy, 0);
            g.pose().scale(ICON / 16F, ICON / 16F, 1F);
            g.renderItem(new net.minecraft.world.item.ItemStack(games.brennan.dungeontrain.registry.ModItems.COSMIC_APPLE.get()), 0, 0);
            g.pose().popPose();
        } else {
            g.setColor(1F, 1F, 1F, alpha * (on ? 1F : 0.5F));
            g.blit(PerkCounters.ADVANCEMENT_ICON, cx, iy, ICON, ICON, 0, 0, PerkCounters.ICON_TEX, PerkCounters.ICON_TEX,
                PerkCounters.ICON_TEX, PerkCounters.ICON_TEX);
            g.setColor(1F, 1F, 1F, 1F);
        }
        g.drawString(font, cost, cx + ICON + ICON_GAP, ty, argb(a, on ? EDGE : OFF_TEXT), false);
    }

    private static int argb(int a, int rgb) {
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
