package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkTree;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Map;

/**
 * One star as the web page's test mode draws it: halo, core, tier-3 dot, one ring segment per unlock
 * level (filled per point), the name, and the Engine as a clock ({@link EngineClock}). Locked stars are
 * grey and faint; a locked star a solid line reaches is brighter. Sizes come from {@link SkyPalette}
 * with the floor rule, so the fit view still reads.
 */
@OnlyIn(Dist.CLIENT)
final class StarPainter {

    static final long UNLOCK_MS = 300, POINT_MS = 250, BURST_MS = 400;
    private static final double BREATHE_MS = 2000;
    /** Ring segment half-thickness, editor units (web {@code w = 1.6}). */
    private static final double SEG_HALF = 1.6;
    private static final double LOCKED_ALPHA = 0.35, REACH_ALPHA = 0.75, REACH_GREY = 0.6, GHOST_ALPHA = 0.4;

    private StarPainter() {}

    /** How this star is lit beyond its own state. */
    /** {@code namePos}: where the name goes (top-left, screen px), or null to leave it out. */
    /** {@code centrePoints}: the Engine shows this many unlock points instead of its clock (the death menu); -1 for the clock. */
    record Look(boolean hovered, boolean selected, int[] namePos, boolean spendable, boolean ghost, boolean glow,
                int centrePoints) {}

    static void render(GuiGraphics g, Font font, PerkTree tree, PerkTree.Perk p, PerkMenuSnapshot snap, SkyView view,
                       int w, int h, Look look, Map<String, Long> unlockedAt, Map<String, Long> pointAt, long now, double fade) {
        double z = view.zoom();
        double x = view.toScreenX(p.x(), w), y = view.toScreenY(p.y(), h);
        double core = SkyPalette.coreRadius(p, z), halo = SkyPalette.haloRadius(p, z);
        if (p.isRoot()) {
            renderEngine(g, font, snap, x, y, core, halo, look, fade);
            return;
        }
        PerkMenuSnapshot.StarState state = snap.stateOf(tree, p.id());
        int colour = SkyPalette.classColour(tree, p);
        boolean tier3 = p.tier() == 3;
        int fill = tier3 ? SkyDraw.strong(colour) : colour;

        double opacity = 1.0;
        if (!look.glow()) {
            if (state == PerkMenuSnapshot.StarState.LOCKED) {
                opacity = LOCKED_ALPHA;
                fill = SkyDraw.grey(fill);
            } else if (state == PerkMenuSnapshot.StarState.REACHABLE) {
                opacity = REACH_ALPHA;
                fill = SkyDraw.mix(fill, SkyDraw.grey(fill), REACH_GREY);
            }
        }
        if (look.ghost()) opacity *= GHOST_ALPHA;
        opacity *= fade;
        double breathe = 0.5 + 0.5 * Math.sin(now / BREATHE_MS * Math.PI * 2);
        long unlockAge = age(unlockedAt, p.id(), now), pointAge = age(pointAt, p.id(), now);

        // halo
        double haloAlpha = look.selected() || look.hovered() ? 1.0 : look.glow() ? 0.7 : 0.35;
        int haloColour = look.selected() ? SkyPalette.GOLD : look.glow() ? colour : fill;
        if (unlockAge >= 0 && unlockAge < UNLOCK_MS) {
            haloColour = SkyDraw.mix(0xFFFFFFFF, fill, unlockAge / (double) UNLOCK_MS);
            haloAlpha = 1.0;
        }
        SkyDraw.ring(g, x, y, halo, 1.0, SkyDraw.withAlpha(haloColour, haloAlpha * opacity));
        if (look.selected()) SkyDraw.ring(g, x, y, halo + 4, 1.0, SkyDraw.withAlpha(SkyPalette.GOLD, 0.5 * fade));
        if (look.spendable() && (state == PerkMenuSnapshot.StarState.UNLOCKED || state == PerkMenuSnapshot.StarState.ACTIVE)
            && snap.points(p.id()) < snap.unlockLevel(p.id())) {
            SkyDraw.ring(g, x, y, halo + 3, 1.0, SkyDraw.withAlpha(SkyPalette.GOLD, (0.25 + 0.3 * breathe) * fade));
        }

        // core: hollow once unlocked with no points, filled otherwise (locked stars too, greyed)
        if (unlockAge >= 0 && unlockAge < UNLOCK_MS) {
            SkyDraw.disc(g, x, y, core, SkyDraw.withAlpha(SkyPalette.BG, opacity));
            SkyDraw.ring(g, x, y, core, 1.4, SkyDraw.withAlpha(fill, opacity));
            SkyDraw.disc(g, x, y, core * unlockAge / (double) UNLOCK_MS, SkyDraw.withAlpha(fill, opacity));
        } else if (state == PerkMenuSnapshot.StarState.UNLOCKED) {
            SkyDraw.disc(g, x, y, core, SkyDraw.withAlpha(SkyPalette.BG, opacity));
            SkyDraw.ring(g, x, y, core, Math.max(1.0, 1.4 * z), SkyDraw.withAlpha(fill, opacity));
        } else {
            SkyDraw.disc(g, x, y, core, SkyDraw.withAlpha(fill, opacity));
        }
        if (tier3) {
            double dot = SkyPalette.TIER3_DOT * core / SkyPalette.CORE[3];
            SkyDraw.disc(g, x, y, Math.max(0.8, dot), SkyDraw.withAlpha(SkyPalette.BG, opacity));
        }

        if (unlockAge >= 0 && unlockAge < BURST_MS) {
            double t = unlockAge / (double) BURST_MS;
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                double d = halo + 20 * z * t;
                SkyDraw.disc(g, x + Math.cos(a) * d, y + Math.sin(a) * d, 1.0, SkyDraw.withAlpha(fill, (1 - t) * fade));
            }
        }

        renderSegments(g, x, y, halo, z, snap.unlockLevel(p.id()), snap.points(p.id()), colour, opacity, pointAge);

    }

    /**
     * A star's name, drawn in a pass after every star so no dot covers a label. Glowing dark round its
     * edge so it reads where it crosses a dot or a line.
     */
    static void renderName(GuiGraphics g, Font font, PerkTree tree, PerkTree.Perk p, PerkMenuSnapshot snap, int[] at,
                           boolean glow, boolean ghost, double fade) {
        PerkMenuSnapshot.StarState state = snap.stateOf(tree, p.id());
        boolean locked = state == PerkMenuSnapshot.StarState.LOCKED && !glow;
        int text = locked ? SkyPalette.MUTED : SkyPalette.FG;
        double opacity = (locked ? 0.6 : state == PerkMenuSnapshot.StarState.REACHABLE && !glow ? 0.85 : 1.0)
            * (ghost ? 0.5 : 1.0) * fade;
        g.pose().pushPose();
        g.pose().translate(at[0], at[1], 0);
        g.pose().scale(SkyPalette.NAME_SCALE, SkyPalette.NAME_SCALE, 1F);
        GlowText.draw(g, font, p.name(), 0, 0, SkyDraw.withAlpha(text, opacity));
        g.pose().popPose();
    }

    /**
     * One segment per unlock level round the star; points fill them. Ten fit a ring at a tenth each;
     * past ten levels the segments shrink so the whole level still fits (web {@code slots = max(10, lvl)}).
     */
    private static void renderSegments(GuiGraphics g, double x, double y, double halo, double z, int level, int points,
                                       int colour, double opacity, long pointAge) {
        if (level <= 0) return;
        int slots = Math.max(SkyPalette.RING_SLOTS, level);
        double half = Math.max(0.9, SEG_HALF * Math.max(z, 0.6));
        double r = halo + Math.max(1.0, SkyPalette.RING_OFFSET * z) + half;
        double slot = Math.PI * 2 / slots;
        double gap = Math.min(SkyPalette.RING_GAP, slot * 0.3);
        int c = SkyDraw.withAlpha(colour, opacity);
        double edge = Math.max(0.6, 0.8 * z);
        for (int i = 0; i < level; i++) {
            double a0 = -Math.PI / 2 + i * slot + gap / 2, a1 = -Math.PI / 2 + (i + 1) * slot - gap / 2;
            if (i < points) {
                double end = a1;
                if (i == points - 1 && pointAge >= 0 && pointAge < POINT_MS) end = a0 + (a1 - a0) * pointAge / (double) POINT_MS;
                SkyDraw.arc(g, x, y, r, half * 2, a0, end, c);
            } else {
                SkyDraw.arc(g, x, y, r + half, edge, a0, a1, c);
                SkyDraw.arc(g, x, y, r - half, edge, a0, a1, c);
                for (double a : new double[] {a0, a1}) {
                    SkyDraw.line(g, x + Math.cos(a) * (r - half), y + Math.sin(a) * (r - half),
                        x + Math.cos(a) * (r + half), y + Math.sin(a) * (r + half), edge, c, c);
                }
            }
        }
    }

    private static void renderEngine(GuiGraphics g, Font font, PerkMenuSnapshot snap, double x, double y, double core,
                                     double halo, Look look, double fade) {
        SkyDraw.ring(g, x, y, halo, 1.0, SkyDraw.withAlpha(look.selected() ? SkyPalette.GOLD : SkyPalette.GOLD,
            (look.selected() || look.hovered() ? 1.0 : 0.35) * fade));
        if (look.centrePoints() >= 0) EngineClock.renderPoints(g, font, x, y, core, look.centrePoints(), fade);
        else EngineClock.render(g, font, x, y, core, snap.ranksBought(), fade);
    }

    private static long age(Map<String, Long> at, String id, long now) {
        Long t = at.get(id);
        return t == null ? -1 : now - t;
    }
}
