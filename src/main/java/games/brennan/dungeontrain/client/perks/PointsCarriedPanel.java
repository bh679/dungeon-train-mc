package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.client.DeathScreenText;
import games.brennan.dungeontrain.perks.PerkRankCost;
import games.brennan.dungeontrain.perks.PerkTree;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Map;

/**
 * The death page framing over the sky when a life ends with points in perks ({@link DeathPerksPhase}):
 * a kicker, question and narration across the top in the death recap's voice, and along the bottom, in
 * the details bar's place, tiles for what the points bought and the Next Screen button. Everything here
 * fades with one alpha so the drain can hand the same spots over to the menu.
 */
@OnlyIn(Dist.CLIENT)
final class PointsCarriedPanel {

    private static final String KEY = "gui.dungeontrain.perks.death.";
    private static final int TOP_BAND = 64;
    private static final int TILE_W = 92, TILE_H = 30, TILE_GAP = 6, PAD = 8;
    private static final int BUTTON_W = 96, BUTTON_H = 20;
    private static final int TILE_BG = 0x14FFFFFF, TILE_BORDER = 0x33D6C496;
    private static final int VALUE = 0xFFE6D6B0, LABEL = 0xFF9A8F74;
    private static final int BTN_BG = 0xFF585B5E, BTN_LIGHT = 0xFF76797D, BTN_DARK = 0xFF1E1F21, BTN_TEXT = 0xFFEAEAEA;
    private static final int BTN_HOVER_BG = 0xFF6A6D70;

    private final Font font;
    private int buttonX, buttonY;

    PointsCarriedPanel(Font font) {
        this.font = font;
    }

    /** Draw the framing at {@code alpha}; {@code barTop} is where the details bar would start. */
    void render(GuiGraphics g, PerkTree tree, PerkCard names, PerkMenuSnapshot carried, int width, int barTop,
                int mouseX, int mouseY, double alpha) {
        if (alpha <= 0.03) return; // Minecraft draws near-zero alpha text fully opaque
        g.fillGradient(0, 0, width, TOP_BAND, fade(0xE0070B16, alpha), fade(0x00070B16, alpha));
        int cx = width / 2, w = Math.min(DeathScreenText.MAX_CONTENT_WIDTH, width - PAD * 2);
        DeathScreenText.drawCenteredLine(g, font, Component.translatable(KEY + "kicker"), cx, 8, fade(DeathScreenText.KICKER, alpha));
        int y = DeathScreenText.drawQuestion(g, font, question(carried), cx, w, 8 + DeathScreenText.KICKER_STEP - 4,
            fade(DeathScreenText.QUESTION, alpha));
        DeathScreenText.drawNarration(g, font, Component.translatable(KEY + "narration").getString(), cx, w, y - 4,
            fade(DeathScreenText.NARR, alpha));

        g.fill(0, barTop, width, barTop + PerkDetailsBar.HEIGHT, fade(0xF20D0B2A, alpha));
        g.fill(0, barTop, width, barTop + 1, fade(0xFF3A3F6A, alpha));
        int ty = barTop + (PerkDetailsBar.HEIGHT - TILE_H) / 2;
        int x = PAD;
        int right = width - PAD - BUTTON_W - TILE_GAP;
        x = tile(g, x, ty, right, String.valueOf(PerkRankCost.totalCost(carried.ranksBought())), "tile.spent", alpha);
        x = tile(g, x, ty, right, String.valueOf(carried.ranksBought()), "tile.ranks", alpha);
        x = tile(g, x, ty, right, String.valueOf(carried.active().size()), "tile.perks", alpha);
        String top = topPerk(carried);
        if (top != null) {
            tree.perk(top).ifPresent(p -> tile(g, PAD + 3 * (TILE_W + TILE_GAP), ty, right,
                names.name(p) + " " + carried.points(top), "tile.top", alpha));
        }

        buttonX = width - PAD - BUTTON_W;
        buttonY = barTop + (PerkDetailsBar.HEIGHT - BUTTON_H) / 2;
        nextButton(g, font, buttonX, buttonY, BUTTON_W, BUTTON_H, overButton(mouseX, mouseY), alpha);
    }

    boolean overButton(double mx, double my) {
        return mx >= buttonX && mx < buttonX + BUTTON_W && my >= buttonY && my < buttonY + BUTTON_H;
    }

    /** One stat tile: the value over its label. Returns the next tile's x; skipped when it would cross {@code right}. */
    private int tile(GuiGraphics g, int x, int y, int right, String value, String labelKey, double alpha) {
        if (x + TILE_W > right) return x;
        g.fill(x, y, x + TILE_W, y + TILE_H, fade(TILE_BG, alpha));
        g.renderOutline(x, y, TILE_W, TILE_H, fade(TILE_BORDER, alpha));
        String v = font.plainSubstrByWidth(value, TILE_W - 8);
        g.drawString(font, v, x + (TILE_W - font.width(v)) / 2, y + 5, fade(VALUE, alpha), false);
        String label = font.plainSubstrByWidth(Component.translatable(KEY + labelKey).getString(), TILE_W - 6);
        g.drawString(font, label, x + (TILE_W - font.width(label)) / 2, y + 17, fade(LABEL, alpha), false);
        return x + TILE_W + TILE_GAP;
    }

    /** "N points went into M perks this life.", figures marked for the death screen's white numbers. */
    private static String question(PerkMenuSnapshot carried) {
        return Component.translatable(KEY + "question", figure(carried.ranksBought()), figure(carried.active().size())).getString();
    }

    private static String figure(int n) {
        return DeathScreenText.NUM_START + String.valueOf(n) + DeathScreenText.NUM_END;
    }

    /** The perk holding the most points (first in order on a tie), or null when none holds any. */
    static String topPerk(PerkMenuSnapshot carried) {
        String best = null;
        int most = 0;
        for (Map.Entry<String, Integer> e : carried.active().entrySet()) {
            if (e.getValue() > most) {
                best = e.getKey();
                most = e.getValue();
            }
        }
        return best;
    }

    /** The death recap's grey bevel button, saying Next Screen. */
    static void nextButton(GuiGraphics g, Font font, int x, int y, int w, int h, boolean hover, double alpha) {
        g.fill(x, y, x + w, y + h, fade(hover ? BTN_HOVER_BG : BTN_BG, alpha));
        g.fill(x, y, x + w, y + 2, fade(BTN_LIGHT, alpha));
        g.fill(x, y, x + 2, y + h, fade(BTN_LIGHT, alpha));
        g.fill(x, y + h - 2, x + w, y + h, fade(BTN_DARK, alpha));
        g.fill(x + w - 2, y, x + w, y + h, fade(BTN_DARK, alpha));
        Component text = Component.translatable("gui.dungeontrain.death.continue");
        g.drawString(font, text, x + (w - font.width(text)) / 2, y + (h - font.lineHeight) / 2 + 1, fade(BTN_TEXT, alpha), false);
    }

    static int fade(int argb, double alpha) {
        int a = (int) Math.round(((argb >>> 24) & 0xFF) * Math.max(0, Math.min(1, alpha)));
        return (a << 24) | (argb & 0xFFFFFF);
    }
}
