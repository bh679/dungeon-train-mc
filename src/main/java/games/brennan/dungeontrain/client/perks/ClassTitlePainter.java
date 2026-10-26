package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkTree;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Class titles, placed as the web page's {@code labelSpot} does: on the line from the Engine through
 * the class's Engine-linked perk, 40 units past the class's farthest star, slid outward (then inward)
 * until the label clears other labels and stars. Placement runs in sky units with the real text widths
 * converted back, so a bigger font at a small zoom still finds room. Titles shrink to 0.75 on narrow
 * screens.
 */
@OnlyIn(Dist.CLIENT)
final class ClassTitlePainter {

    private static final double PAST_FARTHEST = 40;
    private static final int NARROW_WIDTH = 640;

    private ClassTitlePainter() {}

    private record Spot(double x, double y, double hw, double hh) {}

    static void render(GuiGraphics g, Font font, PerkTree tree, ClassVisibility vis, String glow, SkyView view, int w, int h,
                       double fade) {
        float scale = w < NARROW_WIDTH ? 0.75F : 1F;
        double z = view.zoom();
        List<Spot> placed = new ArrayList<>();
        for (PerkTree.PerkClass c : tree.classes()) {
            // only the drawn perks place the title; a hidden class is named once one of its perks is unlocked
            if (!vis.titleShown(c.id())) continue;
            List<PerkTree.Perk> ps = tree.perksOf(c.id()).stream().filter(p -> !vis.hidden(p)).toList();
            if (ps.isEmpty()) continue;
            String title = c.name().toUpperCase(Locale.ROOT);
            double hwPx = font.width(title) * scale / 2.0 + 4, hhPx = font.lineHeight * scale / 2.0 + 1;
            double hw = hwPx / z, hh = hhPx / z;
            PerkTree.Perk first = tree.firstPerkOf(c.id()).filter(p -> !vis.hidden(p)).orElse(null);
            double a;
            if (first != null) a = Math.atan2(first.y() - PerkTree.CENTRE, first.x() - PerkTree.CENTRE);
            else {
                double sx = 0, sy = 0;
                for (PerkTree.Perk p : ps) { sx += p.x() - PerkTree.CENTRE; sy += p.y() - PerkTree.CENTRE; }
                a = Math.atan2(sy, sx);
            }
            double r0 = ps.stream().mapToDouble(PerkTree.Perk::distanceToCentre).max().orElse(0) + PAST_FARTHEST;
            Spot spot = place(tree, a, r0, hw, hh, placed);
            placed.add(spot);
            double sx = view.toScreenX(spot.x(), w), sy = view.toScreenY(spot.y(), h);
            // the title takes its colour once a point is in one of its perks (or while it glows in the list)
            boolean lit = vis.titleLit(c.id(), glow);
            double alpha = (lit ? 1.0 : 0.55) * (c.id().equals(vis.preview()) ? 0.4 : 1.0) * fade;
            int colour = SkyDraw.withAlpha(lit ? c.colour() : SkyPalette.MUTED, alpha);
            g.pose().pushPose();
            g.pose().translate(sx, sy, 0);
            g.pose().scale(scale, scale, 1F);
            g.drawString(font, title, -font.width(title) / 2, -font.lineHeight / 2, colour, true);
            g.pose().popPose();
        }
    }

    /**
     * Where a title goes: on its class's line out from the Engine, slid outward (then inward) until it
     * clears the stars and the titles already placed. Failing that, nudged a little either side of the
     * line. Other titles are a hard rule: if no spot clears the stars too, the first spot clear of every
     * title wins, so titles never overlap each other.
     */
    private static Spot place(PerkTree tree, double a, double r0, double hw, double hh, List<Spot> placed) {
        double[] nudges = {0, 0.06, -0.06, 0.12, -0.12, 0.2, -0.2, 0.3, -0.3};
        Spot titlesOnly = null;
        for (double da : nudges) {
            for (double dr = 0; dr <= 240; dr += 8) {
                Spot s = at(a + da, r0 + dr, hw, hh);
                if (!clearOfTitles(s, placed)) continue;
                if (clearOfStars(tree, s)) return s;
                if (titlesOnly == null) titlesOnly = s;
            }
            for (double dr = -8; dr >= -80; dr -= 8) {
                Spot s = at(a + da, r0 + dr, hw, hh);
                if (!clearOfTitles(s, placed)) continue;
                if (clearOfStars(tree, s)) return s;
                if (titlesOnly == null) titlesOnly = s;
            }
        }
        if (titlesOnly != null) return titlesOnly;
        // crowded beyond reach: keep pushing outward until clear of every title
        for (double dr = 248; ; dr += 8) {
            Spot s = at(a, r0 + dr, hw, hh);
            if (clearOfTitles(s, placed) || dr > 2000) return s;
        }
    }

    private static Spot at(double a, double r, double hw, double hh) {
        return new Spot(PerkTree.CENTRE + r * Math.cos(a), PerkTree.CENTRE + r * Math.sin(a), hw, hh);
    }

    private static boolean clearOfStars(PerkTree tree, Spot s) {
        for (PerkTree.Perk p : tree.perks()) {
            double pw = p.name().length() * 3 + 8;
            if (Math.abs(p.x() - s.x()) < s.hw() + pw - 8 && Math.abs(p.y() - s.y()) < s.hh() + 18) return false;
        }
        return true;
    }

    /** Clear of every placed title, with a small margin. */
    private static boolean clearOfTitles(Spot s, List<Spot> placed) {
        for (Spot q : placed) {
            if (Math.abs(q.x() - s.x()) < s.hw() + q.hw() + 2 && Math.abs(q.y() - s.y()) < s.hh() + q.hh() + 2) return false;
        }
        return true;
    }
}
