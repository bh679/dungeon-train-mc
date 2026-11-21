package games.brennan.dungeontrain.client.perks;

import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Random;

/**
 * The 140 faint background dots of the web page, in sky space so they pan with the constellation,
 * each twinkling on its own 3 s cycle. Seeded once per screen open.
 */
@OnlyIn(Dist.CLIENT)
final class StarField {

    private static final int COUNT = 140;
    private static final int DOT = 0xFFE8E4D6;
    private static final double TWINKLE_MS = 3000;

    private final double[] x = new double[COUNT];
    private final double[] y = new double[COUNT];
    private final double[] r = new double[COUNT];
    private final double[] base = new double[COUNT];
    private final double[] phase = new double[COUNT];

    StarField(long seed) {
        Random rng = new Random(seed);
        for (int i = 0; i < COUNT; i++) {
            x[i] = rng.nextDouble() * 800;
            y[i] = rng.nextDouble() * 800;
            r[i] = rng.nextDouble() * 1.2 + 0.2;
            base[i] = rng.nextDouble() * 0.5 + 0.15;
            phase[i] = rng.nextDouble();
        }
    }

    void render(GuiGraphics g, SkyView view, int w, int h, long nowMs, double fade) {
        for (int i = 0; i < COUNT; i++) {
            double sx = view.toScreenX(x[i], w), sy = view.toScreenY(y[i], h);
            if (sx < -2 || sy < SkyView.HEADER - 2 || sx > w + 2 || sy > h + 2) continue;
            double t = ((nowMs / TWINKLE_MS) + phase[i]) % 1.0;
            double tw = 1 - 0.65 * (t < 0.5 ? t * 2 : (1 - t) * 2);
            double radius = Math.max(0.6, r[i] * Math.max(0.5, view.zoom()));
            SkyDraw.disc(g, sx, sy, radius, SkyDraw.withAlpha(DOT, base[i] * tw * fade));
        }
    }
}
