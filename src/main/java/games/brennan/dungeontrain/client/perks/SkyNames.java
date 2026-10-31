package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkTree;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Where the perk names sit on the sky: which stars get a name, the spots that keep names clear of each
 * other and of the class titles, and the thin leader line from each name to its star. Split out of
 * {@link PerksScreen}; it reads the frame through {@link Sky} and keeps no state.
 */
@OnlyIn(Dist.CLIENT)
final class SkyNames {

    /** How far a label's leader line starts off the label's edge, GUI px. */
    private static final double LEADER_GAP = 1.5;

    /** What a frame of the sky is drawn from. */
    record Sky(Font font, PerkTree tree, PerkTree drawn, ClassVisibility vis, SkyView view, int width, int skyH) {}

    private SkyNames() {}

    /**
     * A thin line from each label to its perk: from just off the label's side nearest the dot, to the
     * middle of the dot. Drawn under the stars, so the dot sits on top of its line.
     */
    static void renderLeaders(GuiGraphics g, Sky sky, Map<String, int[]> names, double fade) {
        PerkTree drawn = sky.drawn(); ClassVisibility vis = sky.vis(); SkyView view = sky.view(); Font font = sky.font(); int width = sky.width();
        for (Map.Entry<String, int[]> e : names.entrySet()) {
            PerkTree.Perk p = drawn.perk(e.getKey()).orElse(null);
            if (p == null || vis.hidden(p)) continue;
            double cx = view.toScreenX(p.x(), width), cy = view.toScreenY(p.y(), sky.skyH());
            int[] at = e.getValue();
            double x0 = at[0], y0 = at[1];
            double x1 = x0 + Math.ceil(font.width(p.name()) * SkyPalette.NAME_SCALE), y1 = y0 + Math.ceil(font.lineHeight * SkyPalette.NAME_SCALE);
            // nearest point of the label to the dot's middle, stepped just off the label toward the dot
            double nx = Math.max(x0, Math.min(cx, x1)), ny = Math.max(y0, Math.min(cy, y1));
            double dx = cx - nx, dy = cy - ny, d = Math.hypot(dx, dy);
            if (d < 2) continue;
            double sx = nx + dx / d * LEADER_GAP, sy = ny + dy / d * LEADER_GAP;
            int c = SkyDraw.withAlpha(SkyPalette.FG, 0.45 * (vis.ghost(p) ? 0.5 : 1.0) * fade);
            SkyDraw.line(g, sx, sy, cx, cy, 0.75, c, c);
        }
    }

    /**
     * Where each class's perk names would sit with that class hovered (all its perks named, laid out as
     * {@link #layoutNames} does then), for every class at once: the boxes the class titles keep clear of.
     */
    static List<NameLayout.Box> allClassNameBoxes(Sky sky) {
        PerkTree drawn = sky.drawn(); PerkTree tree = sky.tree(); ClassVisibility vis = sky.vis(); SkyView view = sky.view(); Font font = sky.font(); int width = sky.width();
        Map<String, double[]> stars = new HashMap<>();
        Map<String, List<PerkTree.Perk>> byClass = new HashMap<>();
        for (PerkTree.Perk p : drawn.perks()) {
            if (vis.hidden(p) || p.isRoot()) continue;
            stars.put(p.id(), new double[] {view.toScreenX(p.x(), width), view.toScreenY(p.y(), sky.skyH()), SkyPalette.haloRadius(p, view.zoom())});
            byClass.computeIfAbsent(p.cls(), k -> new ArrayList<>()).add(p);
        }
        int h = (int) Math.ceil(font.lineHeight * SkyPalette.NAME_SCALE);
        List<NameLayout.Box> out = new ArrayList<>();
        for (List<PerkTree.Perk> perks : byClass.values()) {
            List<NameLayout.Want> wants = perks.stream()
                .sorted(Comparator.comparingDouble((PerkTree.Perk p) -> -stars.get(p.id())[0]))
                .map(p -> {
                    double[] st = stars.get(p.id());
                    return new NameLayout.Want(p.id(), st[0], st[1], st[2], (int) Math.ceil(font.width(p.name()) * SkyPalette.NAME_SCALE), h, true);
                })
                .toList();
            NameLayout.place(wants, List.of(), stars).forEach((id, at) -> tree.perk(id).ifPresent(p -> out.add(
                new NameLayout.Box(at[0], at[1], at[0] + Math.ceil(font.width(p.name()) * SkyPalette.NAME_SCALE), at[1] + h))));
        }
        return out;
    }

    /**
     * Which perk names show and where, so none overlaps another name, a class title or a star. Every
     * visible star asks; {@code rank} orders them (0 kept, 1 hovered, 2 glowing or featured, 3 unlocked,
     * 4 the rest) and fully zoomed out only ranks up to 2 are named. A name with no free spot is left out.
     */
    static Map<String, int[]> layoutNames(Sky sky, String glow, boolean zoomedOut, ToIntFunction<PerkTree.Perk> rank,
                                          List<NameLayout.Box> titles) {
        PerkTree drawn = sky.drawn(); ClassVisibility vis = sky.vis(); SkyView view = sky.view(); Font font = sky.font(); int width = sky.width();
        Map<String, double[]> stars = new HashMap<>();
        List<PerkTree.Perk> visible = new ArrayList<>();
        for (PerkTree.Perk p : drawn.perks()) {
            if (vis.hidden(p)) continue;
            visible.add(p);
            stars.put(p.id(), new double[] {view.toScreenX(p.x(), width), view.toScreenY(p.y(), sky.skyH()), SkyPalette.haloRadius(p, view.zoom())});
        }
        List<NameLayout.Want> wants = new ArrayList<>();
        // a class hovered in the list: only its perks are named, and every one of them is
        boolean classHover = glow != null;
        visible.stream()
            .filter(p -> !p.isRoot())
            .filter(p -> !classHover || glow.equals(p.cls()))
            .filter(p -> !zoomedOut || rank.applyAsInt(p) <= 2)
            // every visible star asks for its name; the layout fits as many as there is room for, most
            // important first (kept, hovered, glowing, the focus's neighbours, unlocked, then nearest the Engine)
            // hovered and kept stars (and a hovered class) first; then the rightmost perk wins its spot
            .sorted(Comparator.comparingInt((PerkTree.Perk p) -> rank.applyAsInt(p) <= 2 ? 0 : 1)
                .thenComparingDouble(p -> -stars.get(p.id())[0]))
            .forEach(p -> {
                double[] s = stars.get(p.id());
                wants.add(new NameLayout.Want(p.id(), s[0], s[1], s[2],
                    (int) Math.ceil(font.width(p.name()) * SkyPalette.NAME_SCALE), (int) Math.ceil(font.lineHeight * SkyPalette.NAME_SCALE),
                    rank.applyAsInt(p) <= 2));
            });
        return NameLayout.place(wants, titles, stars);
    }
}
