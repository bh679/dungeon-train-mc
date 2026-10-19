package games.brennan.dungeontrain.client.wind;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.HudText;
import games.brennan.dungeontrain.wind.TrainExposure;
import games.brennan.dungeontrain.wind.WindProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The train-wind read-out — <b>F3 + 8</b>, a sibling of the F3+4 train panel and the F3+5 shader panel, behind
 * the same debug access grant. Drawn bottom-right (clear of the hotbar, the train panel and the shader panel):
 * where the wind starts and how far past it you are, the stage in force there, the wind now and the wind the
 * streaks are already showing, whether you are exposed and why not, what the air/sneak factors do to your
 * push, and a 10-second graph of wind now / wind shown / push applied.
 *
 * <p>Untranslated English, like the other DT debug panels and vanilla's F3 screen.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class WindDebugHud {

    private static final int PAD = 3;
    private static final int MARGIN = 4;
    private static final int BOTTOM_CLEAR = 28;
    private static final int LINE_GAP = 1;
    private static final int GRAPH_H = 36;
    private static final int BACKDROP = 0xA0000000;
    private static final int GRID = 0x40FFFFFF;
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_IDLE = 0xFF999999;
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_EXPOSED = 0xFFFF9070;
    private static final int COLOR_SHELTERED = 0xFF90FF90;
    private static final int COLOR_NOW = 0xFF6EA6EA;
    private static final int COLOR_AHEAD = 0xFFF0B060;
    private static final int COLOR_APPLIED = 0xFFFF6060;

    private record Line(String text, int color) {}

    private WindDebugHud() {}

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(
                ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "wind_debug_hud"),
                (LayeredDraw.Layer) (graphics, deltaTracker) -> render(graphics));
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        WindDebugState.reset();
    }

    private static void render(GuiGraphics g) {
        if (!WindDebugState.visible()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.font == null) return;

        List<Line> lines = buildLines(WindDebugState.frame());
        int lineHeight = HudText.scaledLineHeight(mc.font) + LINE_GAP;
        int widest = 200;
        for (Line l : lines) widest = Math.max(widest, HudText.scaledWidth(mc.font, l.text()));
        boolean graph = WindDebugState.frame() != null;

        int width = widest + PAD * 2;
        int height = lines.size() * lineHeight - LINE_GAP + PAD * 2 + (graph ? GRAPH_H + PAD + lineHeight : 0);
        int left = Math.max(MARGIN, g.guiWidth() - MARGIN - width);
        int top = Math.max(MARGIN, g.guiHeight() - BOTTOM_CLEAR - height);
        g.fill(left, top, left + width, top + height, BACKDROP);

        int y = top + PAD;
        for (Line l : lines) {
            HudText.drawScaled(g, mc.font, l.text(), left + PAD, y, l.color(), true);
            y += lineHeight;
        }
        if (graph) {
            drawGraph(g, left + PAD, y + 1, widest, GRAPH_H);
            HudText.drawScaled(g, mc.font, "last 10 s:  wind now   shown (lead)   push applied",
                    left + PAD, y + GRAPH_H + PAD, COLOR_IDLE, true);
            int ly = y + GRAPH_H + PAD + lineHeight - 3;
            int x0 = left + PAD + HudText.scaledWidth(mc.font, "last 10 s:  ");
            g.fill(x0, ly, x0 + HudText.scaledWidth(mc.font, "wind now"), ly + 1, COLOR_NOW);
            int x1 = x0 + HudText.scaledWidth(mc.font, "wind now   ");
            g.fill(x1, ly, x1 + HudText.scaledWidth(mc.font, "shown (lead)"), ly + 1, COLOR_AHEAD);
            int x2 = x1 + HudText.scaledWidth(mc.font, "shown (lead)   ");
            g.fill(x2, ly, x2 + HudText.scaledWidth(mc.font, "push applied"), ly + 1, COLOR_APPLIED);
        }
    }

    private static List<Line> buildLines(WindDebugState.Frame f) {
        List<Line> out = new ArrayList<>(10);
        out.add(new Line("DT wind  (F3+8)", COLOR_TITLE));
        if (f == null) {
            out.add(new Line(WindDebugState.idleReason(), COLOR_IDLE));
            return out;
        }
        out.add(new Line(String.format(Locale.ROOT, "Start X %d   you are %s blocks past it",
                f.startX(), f.d() < 0 ? String.format(Locale.ROOT, "%.0f (before:", f.d()) + " calm)"
                        : String.format(Locale.ROOT, "%.0f", f.d())), COLOR_TEXT));
        WindProfile.Stage s = f.stage();
        if (s != null) {
            out.add(new Line(String.format(Locale.ROOT,
                    "Here: steady %.4f ±%.0f%%  gusts %.1f/min (cap 60), 0.1-%.1fs long (cap 5)",
                    s.base(), s.flutter() * 100, s.gustsPerMin(), s.gustTicks() / 20.0),
                    COLOR_IDLE));
            out.add(new Line(String.format(Locale.ROOT, "      gust peak %.3f  veer ±%.0f°",
                    s.gustStrength(), s.gustVeer()), COLOR_IDLE));
            out.add(new Line(String.format(Locale.ROOT, "       wander ±%.0f° over ~%.0fs",
                    s.wander(), s.wanderSeconds()), COLOR_IDLE));
        }
        out.add(new Line(String.format(Locale.ROOT, "Now:   %.4f toward %s  gust %.0f%%",
                f.now().strength(), heading(f.now().headingRad()), f.now().gust01() * 100), COLOR_NOW));
        out.add(new Line(String.format(Locale.ROOT, "Shown: %.4f toward %s  (+%dt)   %s",
                f.ahead().strength(), heading(f.ahead().headingRad()), f.leadTicks(),
                f.windyHook() ? String.format(Locale.ROOT, "Windy %.2f%s", f.windyStrength(),
                        f.windyStrength() < 0.08f ? " (calm)" : f.windyStrength() >= 0.75f ? " (strong)" : "")
                        : "DT streaks (no Windy hook)"), COLOR_AHEAD));
        out.add(new Line(String.format(Locale.ROOT,
                "Streaks: %.1f b/s with the wind, density x%.1f, + carriage %.1f, %.1f b/s",
                WindyDrive.streakSpeed() * 20, WindyDrive.density(),
                WindyDrive.trainVx() * 20, WindyDrive.trainVz() * 20), COLOR_AHEAD));

        String you;
        int youColor;
        if (f.skip() != null) {
            you = "You: not pushed (" + f.skip() + ")";
            youColor = COLOR_IDLE;
        } else {
            you = "You: " + describe(f.exposure());
            youColor = f.exposure().exposed() ? COLOR_EXPOSED : COLOR_SHELTERED;
        }
        out.add(new Line(you + (f.onGround() ? "   on ground" : "   IN THE AIR") + (f.sneaking() ? "   sneaking" : ""),
                youColor));
        out.add(new Line(String.format(Locale.ROOT, "Push: %.4f applied (%.4f if exposed)   vel %.3f, %.3f  (%.2f b/s)",
                f.applied(), f.wouldBe(), f.velX(), f.velZ(),
                Math.sqrt(f.velX() * f.velX() + f.velZ() * f.velZ()) * 20), COLOR_APPLIED));
        return out;
    }

    private static String describe(TrainExposure.Result r) {
        return switch (r) {
            case EXPOSED -> "EXPOSED";
            case IN_TUNNEL -> "sheltered (tunnel / terrain overhead)";
            case UNDER_COVER -> "sheltered (under cover)";
            case IN_LEE -> "sheltered (in a lee)";
            case NOT_ON_TRAIN -> "not over the train";
        };
    }

    /** Heading as degrees plus which way that is relative to the track (+X = the way the train runs). */
    private static String heading(double rad) {
        double deg = ((Math.toDegrees(rad) % 360) + 360) % 360;
        String rel;
        if (deg < 22.5 || deg >= 337.5) rel = "tailwind";
        else if (deg < 67.5) rel = "+Z, with train";
        else if (deg < 112.5) rel = "+Z cross";
        else if (deg < 157.5) rel = "+Z, against";
        else if (deg < 202.5) rel = "headwind";
        else if (deg < 247.5) rel = "-Z, against";
        else if (deg < 292.5) rel = "-Z cross";
        else rel = "-Z, with train";
        return String.format(Locale.ROOT, "%.0f° %s", deg, rel);
    }

    private static void drawGraph(GuiGraphics g, int x, int y, int w, int h) {
        double[][] hist = WindDebugState.history();
        double max = 0.001;
        for (double[] series : hist) for (double v : series) max = Math.max(max, v);
        g.fill(x, y + h, x + w, y + h + 1, GRID);
        g.fill(x, y + h / 2, x + w, y + h / 2 + 1, GRID);
        plot(g, hist[1], x, y, w, h, max, COLOR_AHEAD);
        plot(g, hist[0], x, y, w, h, max, COLOR_NOW);
        plot(g, hist[2], x, y, w, h, max, COLOR_APPLIED);
        Minecraft mc = Minecraft.getInstance();
        HudText.drawScaled(g, mc.font, String.format(Locale.ROOT, "%.3f", max), x + w - 30, y, COLOR_IDLE, true);
    }

    private static void plot(GuiGraphics g, double[] v, int x, int y, int w, int h, double max, int color) {
        int n = v.length;
        if (n < 2) return;
        int prev = -1;
        for (int px = 0; px < w; px++) {
            int i = (int) ((long) px * (WindDebugState.HISTORY - 1) / Math.max(1, w - 1)) - (WindDebugState.HISTORY - n);
            if (i < 0) continue;
            int py = y + h - (int) Math.round(Math.min(1.0, v[i] / max) * h);
            int a = prev < 0 ? py : Math.min(prev, py);
            int b = prev < 0 ? py : Math.max(prev, py);
            g.fill(x + px, a, x + px + 1, b + 1, color);
            prev = py;
        }
    }
}
