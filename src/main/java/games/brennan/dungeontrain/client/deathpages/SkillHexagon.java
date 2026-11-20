package games.brennan.dungeontrain.client.deathpages;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/**
 * A radar ("spider") chart for the echo page: a hexagon grid with a filled shape whose corners sit
 * at each value's share of the radius. Minecraft's GUI has no polygon fill, so this writes the
 * triangles straight into the GUI buffer (as degenerate quads, both windings, so culling never
 * hides one) and draws the grid lines as thin quads.
 *
 * <p>Corner 0 is at the top; the rest follow clockwise.</p>
 */
public final class SkillHexagon {

    private SkillHexagon() {
    }

    /** The point for corner {@code i} of {@code n} at radius {@code r} around ({@code cx}, {@code cy}). */
    public static float[] corner(float cx, float cy, float r, int i, int n) {
        double a = -Math.PI / 2 + 2 * Math.PI * i / n;
        return new float[] {cx + (float) (r * Math.cos(a)), cy + (float) (r * Math.sin(a))};
    }

    /**
     * How far the {@code n}-cornered shape reaches sideways from its centre, as a share of its
     * radius: the largest {@code |cos|} among its corners (0.87 for a hexagon, 1 for an octagon).
     */
    public static float halfWidth(int n) {
        double most = 0;
        for (int i = 0; i < n; i++) {
            most = Math.max(most, Math.abs(Math.cos(-Math.PI / 2 + 2 * Math.PI * i / n)));
        }
        return (float) most;
    }

    /**
     * Draw the grid (outer ring, half ring, spokes) in {@code gridArgb} and the value shape —
     * {@code values} each 0..max — filled with {@code fillArgb} and edged with {@code lineArgb}.
     */
    public static void draw(GuiGraphics g, float cx, float cy, float r, int[] values, int max,
                            int gridArgb, int fillArgb, int lineArgb) {
        int n = values.length;
        Matrix4f m = g.pose().last().pose();
        VertexConsumer vc = g.bufferSource().getBuffer(RenderType.gui());
        for (int i = 0; i < n; i++) {
            float[] a = corner(cx, cy, r, i, n);
            float[] b = corner(cx, cy, r, (i + 1) % n, n);
            float[] ha = corner(cx, cy, r / 2, i, n);
            float[] hb = corner(cx, cy, r / 2, (i + 1) % n, n);
            line(vc, m, a[0], a[1], b[0], b[1], 0.6F, gridArgb);
            line(vc, m, ha[0], ha[1], hb[0], hb[1], 0.6F, gridArgb);
            line(vc, m, cx, cy, a[0], a[1], 0.5F, gridArgb);
        }
        float[][] pts = new float[n][];
        for (int i = 0; i < n; i++) {
            float share = Math.max(0, Math.min(max, values[i])) / (float) max;
            pts[i] = corner(cx, cy, r * share, i, n);
        }
        for (int i = 0; i < n; i++) {
            float[] a = pts[i];
            float[] b = pts[(i + 1) % n];
            triangle(vc, m, cx, cy, a[0], a[1], b[0], b[1], fillArgb);
        }
        for (int i = 0; i < n; i++) {
            float[] a = pts[i];
            float[] b = pts[(i + 1) % n];
            line(vc, m, a[0], a[1], b[0], b[1], 1.0F, lineArgb);
        }
        g.flush();
    }

    private static void triangle(VertexConsumer vc, Matrix4f m, float x1, float y1, float x2, float y2,
                                 float x3, float y3, int argb) {
        vc.addVertex(m, x1, y1, 0).setColor(argb);
        vc.addVertex(m, x2, y2, 0).setColor(argb);
        vc.addVertex(m, x3, y3, 0).setColor(argb);
        vc.addVertex(m, x3, y3, 0).setColor(argb);
        vc.addVertex(m, x1, y1, 0).setColor(argb);
        vc.addVertex(m, x3, y3, 0).setColor(argb);
        vc.addVertex(m, x2, y2, 0).setColor(argb);
        vc.addVertex(m, x2, y2, 0).setColor(argb);
    }

    /** A straight line {@code w} px thick, as a quad. */
    private static void line(VertexConsumer vc, Matrix4f m, float x1, float y1, float x2, float y2,
                             float w, int argb) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001F) return;
        float nx = -dy / len * w / 2;
        float ny = dx / len * w / 2;
        vc.addVertex(m, x1 + nx, y1 + ny, 0).setColor(argb);
        vc.addVertex(m, x2 + nx, y2 + ny, 0).setColor(argb);
        vc.addVertex(m, x2 - nx, y2 - ny, 0).setColor(argb);
        vc.addVertex(m, x1 - nx, y1 - ny, 0).setColor(argb);
        vc.addVertex(m, x1 + nx, y1 + ny, 0).setColor(argb);
        vc.addVertex(m, x1 - nx, y1 - ny, 0).setColor(argb);
        vc.addVertex(m, x2 - nx, y2 - ny, 0).setColor(argb);
        vc.addVertex(m, x2 + nx, y2 + ny, 0).setColor(argb);
    }
}
