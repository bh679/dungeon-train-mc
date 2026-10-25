package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkTree;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Map;

/**
 * One star: halo, core, tier-3 dot, the 10-slot ring, the name, plus the unlock and point
 * animations. Sizes come from {@link SkyPalette} with the floor rule, so the fit view still reads.
 */
@OnlyIn(Dist.CLIENT)
final class StarPainter {

    static final long UNLOCK_MS = 300, POINT_MS = 250, BURST_MS = 400;
    private static final double BREATHE_MS = 2000;

    private StarPainter() {}

    /**
     * @param unlockedAt perk id to the ms it was seen unlocked (fill animation)
     * @param pointAt    perk id to the ms its last point was seen (ring sweep)
     * @param showName   draw the name under the star
     * @param spendable  apple mode: mark stars that can take a point
     */
    static void render(GuiGraphics g, Font font, PerkTree tree, PerkTree.Perk p, PerkMenuSnapshot snap, SkyView view,
                       int w, int h, boolean hovered, boolean selected, boolean showName, boolean spendable,
                       Map<String, Long> unlockedAt, Map<String, Long> pointAt, long now, double fade) {
        double z = view.zoom();
        double x = view.toScreenX(p.x(), w), y = view.toScreenY(p.y(), h);
        double core = SkyPalette.coreRadius(p, z), halo = SkyPalette.haloRadius(p, z);
        PerkMenuSnapshot.StarState state = snap.stateOf(tree, p.id());
        int colour = SkyPalette.classColour(tree, p);
        boolean root = p.isRoot();
        boolean tier3 = !root && p.tier() == 3;
        int fill = tier3 ? SkyDraw.strong(colour) : colour;
        double breathe = 0.5 + 0.5 * Math.sin(now / BREATHE_MS * Math.PI * 2);

        double opacity = switch (state) {
            case LOCKED -> 0.35;
            case REACHABLE -> 0.55;
            default -> 1.0;
        } * fade;
        if (state == PerkMenuSnapshot.StarState.LOCKED) fill = SkyDraw.grey(fill);

        long unlockAge = age(unlockedAt, p.id(), now), pointAge = age(pointAt, p.id(), now);

        // halo
        double haloAlpha = selected ? 1.0 : hovered ? 1.0 : state == PerkMenuSnapshot.StarState.REACHABLE ? 0.25 + 0.15 * breathe : 0.35;
        int haloColour = selected ? SkyPalette.GOLD : fill;
        if (unlockAge >= 0 && unlockAge < UNLOCK_MS) {
            haloColour = SkyDraw.mix(0xFFFFFFFF, fill, unlockAge / (double) UNLOCK_MS);
            haloAlpha = 1.0;
        }
        SkyDraw.ring(g, x, y, halo, 1.0, SkyDraw.withAlpha(haloColour, haloAlpha * opacity));
        if (selected) SkyDraw.ring(g, x, y, halo + 4, 1.0, SkyDraw.withAlpha(SkyPalette.GOLD, 0.5 * fade));
        if (spendable && (state == PerkMenuSnapshot.StarState.UNLOCKED || state == PerkMenuSnapshot.StarState.ACTIVE) && !root
            && snap.points(p.id()) < SkyPalette.RING_SLOTS) {
            SkyDraw.ring(g, x, y, halo + 3, 1.0, SkyDraw.withAlpha(SkyPalette.GOLD, (0.25 + 0.3 * breathe) * fade));
        }

        // core
        boolean hollow = state == PerkMenuSnapshot.StarState.UNLOCKED || state == PerkMenuSnapshot.StarState.REACHABLE;
        if (unlockAge >= 0 && unlockAge < UNLOCK_MS) {
            // fill from the centre outward over UNLOCK_MS
            SkyDraw.disc(g, x, y, core, SkyDraw.withAlpha(SkyPalette.BG, opacity));
            SkyDraw.ring(g, x, y, core, 1.4, SkyDraw.withAlpha(fill, opacity));
            SkyDraw.disc(g, x, y, core * unlockAge / (double) UNLOCK_MS, SkyDraw.withAlpha(fill, opacity));
        } else if (hollow) {
            SkyDraw.disc(g, x, y, core, SkyDraw.withAlpha(SkyPalette.BG, opacity));
            SkyDraw.ring(g, x, y, core, Math.max(1.0, 1.4 * z), SkyDraw.withAlpha(fill, opacity));
        } else {
            SkyDraw.disc(g, x, y, core, SkyDraw.withAlpha(fill, opacity));
        }
        if (root || tier3) {
            double dot = root ? SkyPalette.ROOT_DOT * core / SkyPalette.ROOT_CORE : SkyPalette.TIER3_DOT * core / SkyPalette.CORE[3];
            SkyDraw.disc(g, x, y, Math.max(0.8, dot), SkyDraw.withAlpha(SkyPalette.BG, opacity));
        }

        // burst of dots on unlock
        if (unlockAge >= 0 && unlockAge < BURST_MS) {
            double t = unlockAge / (double) BURST_MS;
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                double d = halo + 20 * z * t;
                SkyDraw.disc(g, x + Math.cos(a) * d, y + Math.sin(a) * d, 1.0, SkyDraw.withAlpha(fill, (1 - t) * fade));
            }
        }

        // ring
        int pts = root ? 0 : Math.min(SkyPalette.RING_SLOTS, snap.points(p.id()));
        if (state == PerkMenuSnapshot.StarState.ACTIVE && pts > 0) {
            double r = halo + Math.max(1.0, SkyPalette.RING_OFFSET * z);
            double width = Math.max(1.5, SkyPalette.RING_WIDTH * Math.max(z, 0.6));
            double slot = Math.PI * 2 / SkyPalette.RING_SLOTS;
            double pulse = pointAge >= 0 && pointAge < POINT_MS * 2 && pointAge >= POINT_MS ? 0.5 + 0.5 * Math.sin((pointAge - POINT_MS) / (double) POINT_MS * Math.PI) * 0.6 : 0;
            for (int i = 0; i < pts; i++) {
                double a0 = -Math.PI / 2 + i * slot + SkyPalette.RING_GAP / 2, a1 = -Math.PI / 2 + (i + 1) * slot - SkyPalette.RING_GAP / 2;
                if (i == pts - 1 && pointAge >= 0 && pointAge < POINT_MS) a1 = a0 + (a1 - a0) * pointAge / (double) POINT_MS;
                SkyDraw.arc(g, x, y, r, width + pulse, a0, a1, SkyDraw.withAlpha(colour, opacity));
            }
        }

        if (showName && !root) {
            int text = state == PerkMenuSnapshot.StarState.LOCKED ? SkyPalette.MUTED : SkyPalette.FG;
            int alpha = (int) (Math.max(0.35, opacity) * 255);
            String name = p.name();
            int tx = (int) Math.round(x - font.width(name) / 2.0), ty = (int) Math.round(y + halo + 4);
            g.drawString(font, name, tx, ty, (alpha << 24) | (text & 0xFFFFFF), true);
        }
    }

    private static long age(Map<String, Long> at, String id, long now) {
        Long t = at.get(id);
        return t == null ? -1 : now - t;
    }
}
