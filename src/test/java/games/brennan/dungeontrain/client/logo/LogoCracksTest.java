package games.brennan.dungeontrain.client.logo;

import games.brennan.dungeontrain.client.logo.CrackPath.Sample;
import games.brennan.dungeontrain.client.logo.LogoCracks.Shape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static games.brennan.dungeontrain.client.logo.LogoCracks.ATLAS_ROW;
import static games.brennan.dungeontrain.client.logo.LogoCracks.ATLAS_W;
import static games.brennan.dungeontrain.client.logo.LogoCracks.BLOCK;
import static games.brennan.dungeontrain.client.logo.LogoCracks.MIN_GAP;
import static games.brennan.dungeontrain.client.logo.LogoCracks.PARALLEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The crack rules, as the website's landing-logo-ring.test.js checks them for cracks.js. */
class LogoCracksTest {

    private static final double SEG_LEN = LogoRingGeometry.segmentTexels();
    private static final LogoCracks FIELD = new LogoCracks(4, SEG_LEN, LogoCracks.Settings.LOGO);

    private static List<Shape> shapesOf(LogoCracks f, int piece, String... kinds) {
        List<String> k = Arrays.asList(kinds);
        return f.shapes(piece).stream().filter(s -> k.contains(s.kind())).toList();
    }

    private static List<Shape> allOf(String... kinds) {
        List<Shape> out = new ArrayList<>();
        for (int p = 0; p < 4; p++) out.addAll(shapesOf(FIELD, p, kinds));
        return out;
    }

    private static double yInRow(double y) { return LogoCracks.wrapY(y) % ATLAS_ROW; }
    private static double toLongEdge(double y) { return Math.min(yInRow(y), ATLAS_ROW - yInRow(y)); }
    private static boolean atCutEnd(double x) { return Math.min(Math.abs(x), Math.abs(SEG_LEN - x)) < 1; }

    @Test
    @DisplayName("the ring gets cracks, chips and its 3 edge bits")
    void counts() {
        assertTrue(allOf("crack").size() >= 16, "cracks");
        assertTrue(allOf("chip").size() >= 20, "chips");
        assertEquals(3, allOf("scoop", "sliver").size());
    }

    @Test
    @DisplayName("every crack starts on a surface edge, is at least the minimum length and stays on its segment")
    void cracksStartOnEdges() {
        for (Shape c : allOf("crack")) {
            Sample first = c.samples().get(0), last = c.samples().get(c.samples().size() - 1);
            assertTrue(toLongEdge(first.y()) <= 23 || atCutEnd(first.x()), "crack starts on an edge");
            assertTrue(last.dist() >= 140, "crack is long enough");
            assertTrue(c.samples().stream().allMatch(p -> p.x() >= -1 && p.x() <= SEG_LEN + 1), "stays on its segment");
        }
    }

    @Test
    @DisplayName("every chip crosses a long edge or runs in from a cut end")
    void chipsTouchEdges() {
        for (Shape c : allOf("chip")) {
            Sample a = c.samples().get(0), b = c.samples().get(c.samples().size() - 1);
            assertTrue(LogoCracks.wrapRow(a.y()) != LogoCracks.wrapRow(b.y()) || atCutEnd(a.x()) || atCutEnd(b.x()));
        }
    }

    @Test
    @DisplayName("no two stretches running the same way come closer than MIN_GAP, nor hug an edge")
    void spacing() {
        for (int piece = 0; piece < 4; piece++) {
            List<Shape> lines = shapesOf(FIELD, piece, "crack", "chip");
            List<Sample> pts = new ArrayList<>();
            List<Integer> ids = new ArrayList<>();
            for (int id = 0; id < lines.size(); id++) {
                List<Sample> s = lines.get(id).samples();
                for (int i = 0; i < s.size(); i += 3) { pts.add(s.get(i)); ids.add(id); }
            }
            for (int i = 0; i < pts.size(); i++) {
                Sample p = pts.get(i);
                if (Math.abs(p.dx()) > PARALLEL) assertTrue(toLongEdge(p.y()) >= MIN_GAP, "runs along a long edge");
                if (Math.abs(p.dy()) > PARALLEL) assertTrue(Math.min(Math.abs(p.x()), Math.abs(SEG_LEN - p.x())) >= MIN_GAP, "runs along a cut end");
                for (int j = 0; j < pts.size(); j++) {
                    if (i == j) continue;
                    Sample q = pts.get(j);
                    if (ids.get(i).equals(ids.get(j)) && Math.abs(q.dist() - p.dist()) < MIN_GAP * 2.5) continue;
                    assertFalse(LogoCracks.alongside(p, q), "parallel lines too close on segment " + piece);
                }
            }
        }
    }

    @Test
    @DisplayName("regrowing hidden faces changes them and never touches a visible face")
    void regrowHidden() {
        LogoCracks f = new LogoCracks(4, SEG_LEN, LogoCracks.Settings.LOGO);
        double[][] before = new double[4][];
        for (int k = 0; k < 4; k++) before[k] = rowTexels(f, 2, k);
        f.regrowHidden(2, (1 << 1) | (1 << 2));
        assertTrue(Arrays.equals(before[0], rowTexels(f, 2, 0)), "visible face 0 unchanged");
        assertTrue(Arrays.equals(before[3], rowTexels(f, 2, 3)), "visible face 3 unchanged");
        assertFalse(Arrays.equals(before[1], rowTexels(f, 2, 1)) && Arrays.equals(before[2], rowTexels(f, 2, 2)), "hidden faces regrown");
        assertEquals(1, f.regenerations());
        assertTrue(f.takeDirty()[2]);
        assertFalse(f.takeDirty()[2], "dirty flags clear once taken");
    }

    @Test
    @DisplayName("mulberry32 matches cracks.js's first values for seed 4242")
    void randMatchesJs() {
        // Pinned from node running cracks.js's mulberry32(4242), so the two ports stay in step.
        LogoCracks.Rand r = new LogoCracks.Rand(4242);
        assertEquals(0.5467061335220933, r.next(), 1e-15);
        assertEquals(0.27860878920182586, r.next(), 1e-15);
        assertEquals(0.9312369171530008, r.next(), 1e-15);
    }

    private static double[] rowTexels(LogoCracks f, int piece, int face) {
        double[] out = new double[ATLAS_ROW * ATLAS_W];
        int top = piece * BLOCK + face * ATLAS_ROW, n = 0;
        for (int y = top; y < top + ATLAS_ROW; y++) for (int x = 0; x < ATLAS_W; x++) out[n++] = f.depthAt(x, y);
        return out;
    }
}
