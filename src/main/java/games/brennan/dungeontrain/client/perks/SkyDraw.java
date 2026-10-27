package games.brennan.dungeontrain.client.perks;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * Resolution-independent 2D primitives for the constellation: filled discs, ring strokes, arcs and
 * thick lines, all as position-colour triangles so a star stays round at any zoom. Same render-state
 * discipline as {@code SkyDomeDraw}: blend on while drawing, shader colour white, state restored.
 * Colours are ARGB ints; alpha is honoured.
 */
@OnlyIn(Dist.CLIENT)
final class SkyDraw {

    private static final int SEGMENTS = 28;

    private SkyDraw() {}

    private static void begin() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull(); // fans and quads wind either way as the maths falls out
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
    }

    private static void end() {
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    static int withAlpha(int argb, double alpha) {
        int a = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    static int mix(int a, int b, double t) {
        int aa = (a >>> 24), ab = (b >>> 24);
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        int al = (int) (aa + (ab - aa) * t);
        return (al << 24) | (r << 16) | (g << 8) | bl;
    }

    /** Greyscale of a colour, keeping alpha. */
    static int grey(int argb) {
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        int l = (int) (0.3 * r + 0.59 * g + 0.11 * b);
        return (argb & 0xFF000000) | (l << 16) | (l << 8) | l;
    }

    /** The web page's {@code strong()}: more saturated, a little darker. */
    static int strong(int argb) {
        float[] hsl = toHsl(argb);
        float s = Math.min(1F, hsl[1] * 1.35F + 0.1F), l = Math.max(0F, hsl[2] - 0.12F);
        return (argb & 0xFF000000) | fromHsl(hsl[0], s, l);
    }

    private static float[] toHsl(int argb) {
        float r = ((argb >> 16) & 255) / 255F, g = ((argb >> 8) & 255) / 255F, b = (argb & 255) / 255F;
        float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        float h = 0, s = 0, l = (mx + mn) / 2;
        if (mx != mn) {
            float d = mx - mn;
            s = l > 0.5F ? d / (2 - mx - mn) : d / (mx + mn);
            if (mx == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (mx == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
        }
        return new float[] {h, s, l};
    }

    private static int fromHsl(float h, float s, float l) {
        float q = l < 0.5F ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
        int r = Math.round(hue(p, q, h + 1F / 3) * 255), g = Math.round(hue(p, q, h) * 255), b = Math.round(hue(p, q, h - 1F / 3) * 255);
        return (r << 16) | (g << 8) | b;
    }

    private static float hue(float p, float q, float t) {
        t = (t + 1) % 1;
        if (t < 1F / 6) return p + (q - p) * 6 * t;
        if (t < 0.5F) return q;
        if (t < 2F / 3) return p + (q - p) * (2F / 3 - t) * 6;
        return p;
    }

    static void disc(GuiGraphics g, double cx, double cy, double r, int colour) {
        if (r <= 0 || (colour >>> 24) == 0) return;
        begin();
        Matrix4f m = g.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(m, (float) cx, (float) cy, 0).setColor(colour);
        for (int i = 0; i <= SEGMENTS; i++) {
            double a = i * Math.PI * 2 / SEGMENTS;
            bb.addVertex(m, (float) (cx + Math.cos(a) * r), (float) (cy + Math.sin(a) * r), 0).setColor(colour);
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());
        end();
    }

    /** A soft radial glow: {@code colour} at the centre fading to nothing at radius {@code r}. */
    static void glow(GuiGraphics g, double cx, double cy, double r, int colour) {
        if (r <= 0 || (colour >>> 24) == 0) return;
        begin();
        Matrix4f m = g.pose().last().pose();
        int rim = colour & 0x00FFFFFF;
        int n = 72;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(m, (float) cx, (float) cy, 0).setColor(colour);
        for (int i = 0; i <= n; i++) {
            double a = i * Math.PI * 2 / n;
            bb.addVertex(m, (float) (cx + Math.cos(a) * r), (float) (cy + Math.sin(a) * r), 0).setColor(rim);
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());
        end();
    }

    /** A filled pie slice from angle {@code a0} to {@code a1} (radians, clockwise from 3 o'clock, y down). */
    static void pie(GuiGraphics g, double cx, double cy, double r, double a0, double a1, int colour) {
        if (r <= 0 || (colour >>> 24) == 0 || a1 <= a0) return;
        begin();
        Matrix4f m = g.pose().last().pose();
        int n = Math.max(2, (int) Math.ceil(SEGMENTS * (a1 - a0) / (Math.PI * 2)));
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(m, (float) cx, (float) cy, 0).setColor(colour);
        for (int i = 0; i <= n; i++) {
            double a = a0 + (a1 - a0) * i / n;
            bb.addVertex(m, (float) (cx + Math.cos(a) * r), (float) (cy + Math.sin(a) * r), 0).setColor(colour);
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());
        end();
    }

    /** A ring stroke from radius {@code r - w/2} to {@code r + w/2}. */
    static void ring(GuiGraphics g, double cx, double cy, double r, double w, int colour) {
        arc(g, cx, cy, r, w, 0, Math.PI * 2, colour);
    }

    /** An arc stroke between two angles (radians, clockwise from 3 o'clock, y down). */
    static void arc(GuiGraphics g, double cx, double cy, double r, double w, double a0, double a1, int colour) {
        if (r <= 0 || w <= 0 || (colour >>> 24) == 0 || a1 <= a0) return;
        begin();
        Matrix4f m = g.pose().last().pose();
        int n = Math.max(2, (int) Math.ceil(SEGMENTS * (a1 - a0) / (Math.PI * 2)));
        double ri = Math.max(0, r - w / 2), ro = r + w / 2;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i <= n; i++) {
            double a = a0 + (a1 - a0) * i / n;
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            bb.addVertex(m, (float) (cx + c * ro), (float) (cy + s * ro), 0).setColor(colour);
            bb.addVertex(m, (float) (cx + c * ri), (float) (cy + s * ri), 0).setColor(colour);
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());
        end();
    }

    /** A line of width {@code w} with a colour gradient from end A to end B. */
    static void line(GuiGraphics g, double x1, double y1, double x2, double y2, double w, int colourA, int colourB) {
        if (w <= 0 || ((colourA >>> 24) == 0 && (colourB >>> 24) == 0)) return;
        double dx = x2 - x1, dy = y2 - y1, len = Math.hypot(dx, dy);
        if (len < 0.01) return;
        double nx = -dy / len * w / 2, ny = dx / len * w / 2;
        begin();
        Matrix4f m = g.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(m, (float) (x1 + nx), (float) (y1 + ny), 0).setColor(colourA);
        bb.addVertex(m, (float) (x1 - nx), (float) (y1 - ny), 0).setColor(colourA);
        bb.addVertex(m, (float) (x2 - nx), (float) (y2 - ny), 0).setColor(colourB);
        bb.addVertex(m, (float) (x2 + nx), (float) (y2 + ny), 0).setColor(colourB);
        BufferUploader.drawWithShader(bb.buildOrThrow());
        end();
    }

    /**
     * A dashed quadratic curve (the chords): sampled into segments, dash and gap in screen px.
     */
    static void dashedCurve(GuiGraphics g, double x1, double y1, double cx, double cy, double x2, double y2,
                            double w, double dash, double gap, int colour) {
        if ((colour >>> 24) == 0) return;
        int n = 48;
        double px = x1, py = y1, carried = 0;
        boolean on = true;
        for (int i = 1; i <= n; i++) {
            double t = (double) i / n, u = 1 - t;
            double x = u * u * x1 + 2 * u * t * cx + t * t * x2, y = u * u * y1 + 2 * u * t * cy + t * t * y2;
            double segLen = Math.hypot(x - px, y - py), pos = 0;
            while (pos < segLen) {
                double want = (on ? dash : gap) - carried;
                double step = Math.min(want, segLen - pos);
                double sx = px + (x - px) * pos / segLen, sy = py + (y - py) * pos / segLen;
                double ex = px + (x - px) * (pos + step) / segLen, ey = py + (y - py) * (pos + step) / segLen;
                if (on) line(g, sx, sy, ex, ey, w, colour, colour);
                pos += step;
                carried += step;
                if (carried >= (on ? dash : gap) - 1e-6) {
                    on = !on;
                    carried = 0;
                }
            }
            px = x;
            py = y;
        }
    }
}
