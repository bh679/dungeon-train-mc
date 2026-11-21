package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkRankCost;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The Engine as the web page's test mode draws it ({@code engineClock}): the points spent on ranks this
 * life in the middle, and a gold sweep from twelve o'clock showing how far through the current price
 * run the next rank is. It empties when the price steps up; past the shrinking runs every rank raises
 * the price, so the sweep stays full.
 */
final class EngineClock {

    private EngineClock() {}

    /** Where the next rank sits in its price run (web {@code priceProgress}). */
    record Progress(int price, int next, int left, int size, int done) {
        double fraction() {
            return size > 1 ? done / (double) size : 1.0;
        }
    }

    static Progress progress(int ranksBought) {
        int n = Math.max(0, ranksBought);
        int price = PerkRankCost.nextCost(n);
        int start = n;
        while (start > 0 && PerkRankCost.nextCost(start - 1) == price) start--;
        int end = n;
        while (PerkRankCost.nextCost(end + 1) == price) end++;
        return new Progress(price, PerkRankCost.nextCost(end + 1), end - n + 1, end - start + 1, n - start);
    }

    /** Points to buy {@code ranks} ranks from nothing (web {@code totalFor}). */
    static long totalFor(int ranks) {
        long sum = 0;
        for (int i = 0; i < ranks; i++) sum += PerkRankCost.nextCost(i);
        return sum;
    }

    /** The death menu's Engine: the unlock points held, large and gold, for the next life. */
    @OnlyIn(Dist.CLIENT)
    static void renderPoints(GuiGraphics g, Font font, double x, double y, double radius, int points, double fade) {
        SkyDraw.disc(g, x, y, radius, SkyDraw.withAlpha(SkyPalette.BG, fade));
        SkyDraw.ring(g, x, y, radius, 1.4, SkyDraw.withAlpha(SkyPalette.GOLD, fade));
        String n = String.valueOf(points);
        // large, but never wider than the ring: a big balance shrinks to fit inside it
        float scale = (float) Math.min(1.5, radius * 1.6 / Math.max(1, font.width(n)));
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, n, -font.width(n) / 2, -font.lineHeight / 2 + 1,
            (SkyPalette.GOLD & 0xFFFFFF) | ((int) (fade * 255) << 24), true);
        g.pose().popPose();
    }

    @OnlyIn(Dist.CLIENT)
    static void render(GuiGraphics g, Font font, double x, double y, double radius, int ranksBought, double fade) {
        Progress pr = progress(ranksBought);
        double frac = pr.fraction();
        SkyDraw.disc(g, x, y, radius, SkyDraw.withAlpha(SkyPalette.BG, fade));
        SkyDraw.ring(g, x, y, radius, 1.4, SkyDraw.withAlpha(SkyPalette.GOLD, fade));
        double r = Math.max(1, radius - 1.5);
        int sweep = SkyDraw.withAlpha(SkyPalette.GOLD, 0.55 * fade);
        if (frac >= 1) {
            SkyDraw.disc(g, x, y, r, sweep);
        } else if (frac > 0) {
            SkyDraw.pie(g, x, y, r, -Math.PI / 2, -Math.PI / 2 + frac * Math.PI * 2, sweep);
        }
        String n = String.valueOf(totalFor(ranksBought));
        int alpha = (int) (fade * 255) << 24;
        g.drawString(font, n, (int) Math.round(x - font.width(n) / 2.0), (int) Math.round(y - font.lineHeight / 2.0) + 1,
            (SkyPalette.FG & 0xFFFFFF) | alpha, true);
    }
}
