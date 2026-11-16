package games.brennan.dungeontrain.client.nap;

import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The sky behind the nap farewell's "a life ongoing" page: the perks menu's twinkling star field, the
 * constellation's stars dim and unnamed ({@link NapSky}), one path per previous life traced out from
 * the Engine and left at half brightness, then this life's path, which settles gold and keeps
 * regrowing a branch (it can still change). A first life gets {@link #FIRST_LIFE_TRIES} passing paths
 * instead. Each path starts as the one before begins to fade.
 *
 * <p>One per screen. The timeline runs from the page's open time, so stepping back to the page replays
 * it; past paths come out the same every time ({@link NapSkyPaths#pastLife}).</p>
 */
@OnlyIn(Dist.CLIENT)
public final class NapSkyPainter {

    private static final int BG = 0x070B16, DOT = 0xE8E4D6, DIM = 0x8E94A8;
    private static final int PATH = 0xC8D2F0, LIVE = 0xE1E6FA, GOLD = 0xE4B95A, GOLD_STAR = 0xF0D796;
    private static final int FIELD = 140;
    private static final long TWINKLE_MS = 3000, STARS_IN_MS = 600, START_MS = 500;
    private static final long STEP_MS = 160, HOLD_MS = 250, FADE_MS = 700;
    private static final double SETTLE_SLOW = 1.6;
    private static final long REGROW_MS = 3200, REGROW_FADE_MS = 500, REGROW_STEP_MS = 200;
    private static final int FIRST_LIFE_TRIES = 8, MAX_PAST = 24, FAST_AFTER = 6;
    /** The mockup's sky size in pixels: radii and widths below are in those pixels. */
    private static final double MOCK_SIZE = 496.0;

    private record Slot(NapSkyPaths.Path path, long at, boolean keep) {}

    private final NapSky sky;
    private final double[] fx = new double[FIELD], fy = new double[FIELD], fr = new double[FIELD],
        fb = new double[FIELD], fp = new double[FIELD];
    private final List<Slot> slots = new ArrayList<>();
    private final double stepMs;
    private final long liveStart;
    private final NapSkyPaths.Path livePath;
    private final Random liveRng;

    private long openedAt = Long.MIN_VALUE;
    private NapSkyPaths.Path live;
    private final Map<NapSkyPaths.Edge, Long> bornAt = new HashMap<>();
    private List<NapSkyPaths.Edge> fading = List.of();
    private long fadingAt;
    private long nextRegrowAt;

    /** {@code pastLives}: lives before this one; {@code seed}: this visit's own randomness (the live path). */
    public NapSkyPainter(NapSky sky, UUID player, long pastLives, long seed) {
        this.sky = sky;
        Random field = new Random(7L);
        for (int i = 0; i < FIELD; i++) {
            fx[i] = field.nextDouble();
            fy[i] = field.nextDouble();
            fr[i] = field.nextDouble() * 1.2 + 0.4;
            fb[i] = field.nextDouble() * 0.5 + 0.15;
            fp[i] = field.nextDouble();
        }
        this.liveRng = NapSkyPaths.rng(seed);
        long drawn = Math.min(Math.max(0L, pastLives), MAX_PAST);
        this.stepMs = drawn > FAST_AFTER ? STEP_MS * (double) FAST_AFTER / drawn : STEP_MS;
        long at = 0;
        int lastFirst = -1;
        if (pastLives > 0) {
            for (long life = pastLives - drawn; life < pastLives; life++) {
                NapSkyPaths.Path p = NapSkyPaths.pastLife(sky, player, life);
                slots.add(new Slot(p, at, true));
                at += Math.round(p.depth() * stepMs) + HOLD_MS;
                lastFirst = p.first();
            }
        } else {
            for (int i = 0; i < FIRST_LIFE_TRIES - 1; i++) {
                NapSkyPaths.Path p = NapSkyPaths.random(sky, liveRng, lastFirst);
                slots.add(new Slot(p, at, false));
                at += Math.round(p.depth() * stepMs) + HOLD_MS;
                lastFirst = p.first();
            }
        }
        this.liveStart = at;
        this.livePath = NapSkyPaths.random(sky, liveRng, lastFirst);
        this.live = livePath;
    }

    /** Whether this life's path has settled (gold) on the page opened at {@code pageOpenedAt}. */
    public boolean settled(long pageOpenedAt, long now) {
        return now - pageOpenedAt - START_MS - liveStart >= livePath.depth() * STEP_MS * SETTLE_SLOW;
    }

    /**
     * Paint the whole screen ({@code w} x {@code h}): background, field, the constellation centred on
     * {@code (cx, cy)} at {@code size} GUI pixels across, and the paths. {@code alpha}: the page's fade.
     */
    public void render(GuiGraphics g, int w, int h, double cx, double cy, double size,
                       long pageOpenedAt, long now, float alpha) {
        if (pageOpenedAt != openedAt) restart(pageOpenedAt);
        g.fill(0, 0, w, h, argb(BG, alpha));
        double u = size / MOCK_SIZE;
        for (int i = 0; i < FIELD; i++) {
            double t = ((now / (double) TWINKLE_MS) + fp[i]) % 1.0;
            double tw = 1 - 0.65 * (t < 0.5 ? t * 2 : (1 - t) * 2);
            double r = Math.max(0.35, fr[i] * u);
            rect(g, fx[i] * w - r, 16 + fy[i] * (h - 16) - r, r * 2, r * 2, argb(DOT, fb[i] * tw * alpha));
        }
        long t = now - pageOpenedAt;
        double starsIn = Math.min(1.0, t / (double) STARS_IN_MS);
        for (int i = 1; i < sky.size(); i++) {
            double r = (sky.tier(i) == 3 ? 3.0 : sky.tier(i) == 2 ? 2.4 : 2.0) * u;
            disc(g, sx(i, cx, size), sy(i, cy, size), Math.max(0.6, r), argb(DIM, 0.45 * starsIn * alpha));
        }
        long tt = t - START_MS;
        if (tt > 0) {
            for (Slot s : slots) {
                long su = tt - s.at();
                if (su < 0) continue;
                double traced = s.path().depth() * stepMs + HOLD_MS;
                double a = 1.0;
                if (su > traced) a = s.keep() ? Math.max(0.5, 1 - (su - traced) / FADE_MS * 0.5) : 1 - (su - traced) / FADE_MS;
                if (a <= 0) continue;
                drawPath(g, s.path(), su, stepMs, a * alpha, PATH, PATH, s.keep() ? 1.3 : 1.4, cx, cy, size);
            }
            long lu = tt - liveStart;
            if (lu >= 0) drawLive(g, lu, now, alpha, cx, cy, size, pageOpenedAt);
        }
        drawEngine(g, sx(0, cx, size), sy(0, cy, size), u, now, alpha);
    }

    private void restart(long pageOpenedAt) {
        openedAt = pageOpenedAt;
        live = livePath;
        bornAt.clear();
        fading = List.of();
        nextRegrowAt = Long.MAX_VALUE;
    }

    private void drawLive(GuiGraphics g, long lu, long now, float alpha, double cx, double cy, double size, long pageOpenedAt) {
        double step = STEP_MS * SETTLE_SLOW;
        boolean done = settled(pageOpenedAt, now);
        if (done && nextRegrowAt == Long.MAX_VALUE) nextRegrowAt = now + REGROW_MS;
        if (done && now >= nextRegrowAt) {
            NapSkyPaths.Regrowth r = NapSkyPaths.regrow(sky, live, liveRng);
            live = r.path();
            fading = r.removed();
            fadingAt = now;
            r.added().forEach(e -> bornAt.put(e, now));
            nextRegrowAt = now + REGROW_MS;
        }
        double u = size / MOCK_SIZE;
        int lineCol = done ? GOLD : LIVE, starCol = done ? GOLD_STAR : LIVE;
        Map<Long, Integer> minDepth = new HashMap<>();
        bornAt.forEach((e, at) -> minDepth.merge(at, e.depth(), Math::min));
        for (NapSkyPaths.Edge e : live.edges()) {
            Long born = bornAt.get(e);
            double f = born != null
                ? clamp((now - born) / (double) REGROW_STEP_MS - (e.depth() - minDepth.get(born)))
                : clamp(lu / step - (e.depth() - 1));
            if (f <= 0) continue;
            line(g, e, f, 0.75 * alpha, lineCol, 1.8 * u, cx, cy, size);
            if (f >= 1) {
                star(g, e.to(), alpha, starCol, true, cx, cy, size);
                if (done && isTip(e.to())) {
                    double p = 0.5 + 0.5 * Math.sin(now / 400.0 + e.to());
                    ring(g, sx(e.to(), cx, size), sy(e.to(), cy, size), (9 + 3 * p) * u, Math.max(0.5, u), argb(GOLD, 0.5 * p * alpha));
                }
            }
        }
        double k = 1 - (now - fadingAt) / (double) REGROW_FADE_MS;
        if (k > 0) {
            for (NapSkyPaths.Edge e : fading) {
                line(g, e, 1, 0.75 * k * alpha, GOLD, 1.8 * u, cx, cy, size);
                star(g, e.to(), k * alpha, GOLD_STAR, false, cx, cy, size);
            }
        }
    }

    private boolean isTip(int star) {
        for (NapSkyPaths.Edge e : live.edges()) if (e.from() == star) return false;
        return true;
    }

    private void drawPath(GuiGraphics g, NapSkyPaths.Path p, long u, double step, double a, int lineCol, int starCol,
                          double width, double cx, double cy, double size) {
        double unit = size / MOCK_SIZE;
        for (NapSkyPaths.Edge e : p.edges()) {
            double f = clamp(u / step - (e.depth() - 1));
            if (f <= 0) continue;
            line(g, e, f, 0.6 * a, lineCol, width * unit, cx, cy, size);
            if (f >= 1) star(g, e.to(), a, starCol, a > 0.6, cx, cy, size);
        }
    }

    private void drawEngine(GuiGraphics g, double x, double y, double u, long now, float alpha) {
        double br = 0.5 + 0.5 * Math.sin(now / 1000.0 * Math.PI);
        // A soft glow: thin rings stepping outward, fainter as they go, so it reads round, not square.
        double outer = (14 + 4 * br) * u, inner = 5.0 * u;
        int rings = 8;
        for (int i = 0; i < rings; i++) {
            double k = i / (double) (rings - 1);
            double r = inner + (outer - inner) * k;
            ring(g, x, y, r, Math.max(0.5, (outer - inner) / rings + 0.3), argb(GOLD, (0.16 + 0.08 * br) * (1 - k) * alpha));
        }
        disc(g, x, y, Math.max(1.0, 4.5 * u), argb(GOLD_STAR, alpha));
    }

    private void star(GuiGraphics g, int i, double a, int col, boolean ring, double cx, double cy, double size) {
        double u = size / MOCK_SIZE, x = sx(i, cx, size), y = sy(i, cy, size);
        disc(g, x, y, Math.max(0.8, 3.2 * u), argb(col, a));
        if (ring) ring(g, x, y, 7 * u, Math.max(0.5, u), argb(col, 0.35 * a));
    }

    private void line(GuiGraphics g, NapSkyPaths.Edge e, double f, double a, int col, double width,
                      double cx, double cy, double size) {
        double ax = sx(e.from(), cx, size), ay = sy(e.from(), cy, size);
        double bx = sx(e.to(), cx, size), by = sy(e.to(), cy, size);
        segment(g, ax, ay, ax + (bx - ax) * f, ay + (by - ay) * f, Math.max(0.5, width), argb(col, a));
    }

    private double sx(int i, double cx, double size) {
        return cx + (sky.x(i) - NapSky.CENTRE) * size / NapSky.SIZE;
    }

    private double sy(int i, double cy, double size) {
        return cy + (sky.y(i) - NapSky.CENTRE) * size / NapSky.SIZE;
    }

    // ---- primitives: GuiGraphics.fill under a scaled / rotated pose ----

    private static void rect(GuiGraphics g, double x, double y, double w, double h, int argb) {
        if ((argb >>> 24) == 0 || w <= 0 || h <= 0) return;
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale((float) w, (float) h, 1f);
        g.fill(0, 0, 1, 1, argb);
        g.pose().popPose();
    }

    /** A filled disc as a three-band octagon (no overlapping bands, so the alpha stays even). */
    private static void disc(GuiGraphics g, double x, double y, double r, int argb) {
        double k = 0.6 * r;
        rect(g, x - r, y - k, 2 * r, 2 * k, argb);
        rect(g, x - k, y - r, 2 * k, r - k, argb);
        rect(g, x - k, y + k, 2 * k, r - k, argb);
    }

    private static void ring(GuiGraphics g, double x, double y, double r, double width, int argb) {
        int n = Math.max(16, (int) Math.ceil(r * 4));
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            segment(g, x + Math.cos(a0) * r, y + Math.sin(a0) * r, x + Math.cos(a1) * r, y + Math.sin(a1) * r, width, argb);
        }
    }

    private static void segment(GuiGraphics g, double ax, double ay, double bx, double by, double width, int argb) {
        double len = Math.hypot(bx - ax, by - ay);
        if (len <= 0.01 || (argb >>> 24) == 0) return;
        g.pose().pushPose();
        g.pose().translate(ax, ay, 0);
        g.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(by - ay, bx - ax)));
        g.pose().scale((float) len, (float) width, 1f);
        g.pose().translate(0, -0.5f, 0);
        g.fill(0, 0, 1, 1, argb);
        g.pose().popPose();
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static int argb(int rgb, double a) {
        int alpha = (int) Math.round(clamp(a) * 255.0);
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }
}
