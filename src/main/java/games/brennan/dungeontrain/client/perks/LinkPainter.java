package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkTree;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Lines between stars, the web page's rules: a requirement link is a straight gradient line from
 * A's colour to B's; a chord is a dashed gold quadratic curve bulging away from the Engine; a
 * one-way chord marks its target with a small gold circle. Both ends locked greys a link out;
 * both ends active makes it thick and bright.
 */
@OnlyIn(Dist.CLIENT)
final class LinkPainter {

    private LinkPainter() {}

    static void render(GuiGraphics g, PerkTree tree, PerkMenuSnapshot snap, SkyView view, int w, int h, double fade) {
        double z = view.zoom();
        for (PerkTree.Link l : tree.links()) {
            PerkTree.Perk a = tree.perk(l.a()).orElse(null), b = tree.perk(l.b()).orElse(null);
            if (a == null || b == null) continue;
            PerkMenuSnapshot.StarState sa = snap.stateOf(tree, a.id()), sb = snap.stateOf(tree, b.id());
            boolean locked = isLocked(sa) || isLocked(sb);
            boolean active = sa == PerkMenuSnapshot.StarState.ACTIVE && sb == PerkMenuSnapshot.StarState.ACTIVE;
            double x1 = view.toScreenX(a.x(), w), y1 = view.toScreenY(a.y(), h);
            double x2 = view.toScreenX(b.x(), w), y2 = view.toScreenY(b.y(), h);
            if (l.kind() == PerkTree.LinkKind.CROSS) {
                double mx = (a.x() + b.x()) / 2, my = (a.y() + b.y()) / 2;
                double cx = view.toScreenX(PerkTree.CENTRE + (mx - PerkTree.CENTRE) * SkyPalette.CHORD_BULGE, w);
                double cy = view.toScreenY(PerkTree.CENTRE + (my - PerkTree.CENTRE) * SkyPalette.CHORD_BULGE, h);
                int colour = locked ? SkyPalette.LOCKED_GREY : SkyPalette.GOLD;
                double alpha = locked ? 0.18 : active ? 0.9 : 0.5;
                double width = active ? 2.2 : Math.max(1.0, 1.0 * z);
                SkyDraw.dashedCurve(g, x1, y1, cx, cy, x2, y2, width, Math.max(2, 3 * z), Math.max(2, 4 * z),
                    SkyDraw.withAlpha(colour, alpha * fade));
                if (l.oneWay()) {
                    SkyDraw.ring(g, x2, y2, SkyPalette.scaled(9, z, 5), 1, SkyDraw.withAlpha(colour, 0.6 * fade));
                }
            } else {
                int ca = SkyPalette.classColour(tree, a), cb = SkyPalette.classColour(tree, b);
                double alpha = locked ? 0.18 : active ? 0.95 : 0.55;
                double width = active ? 2.8 : Math.max(1.0, 1.2 * z);
                if (locked) { ca = SkyPalette.LOCKED_GREY; cb = SkyPalette.LOCKED_GREY; }
                SkyDraw.line(g, x1, y1, x2, y2, width, SkyDraw.withAlpha(ca, alpha * fade), SkyDraw.withAlpha(cb, alpha * fade));
            }
        }
    }

    private static boolean isLocked(PerkMenuSnapshot.StarState s) {
        return s == PerkMenuSnapshot.StarState.LOCKED || s == PerkMenuSnapshot.StarState.REACHABLE;
    }
}
