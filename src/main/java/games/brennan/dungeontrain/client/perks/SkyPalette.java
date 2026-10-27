package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkTree;

/** The web page's colours and star sizes (perks.css / perks.js), in one place. */
final class SkyPalette {

    private SkyPalette() {}

    static final int BG = 0xFF070B16;
    static final int FG = 0xFFE8E4D6;
    static final int MUTED = 0xFF8E94A8;
    static final int LINE = 0xFF2A3350;
    static final int GOLD = 0xFFE4B95A;
    static final int PANEL = 0xF0111A30;
    /** Perk names on the sky are drawn at this scale so more of them fit. */
    static final float NAME_SCALE = 0.6F;
    static final int LOCKED_GREY = 0xFF8E94A8;

    /** Core radius by tier (index 1..3), editor units. */
    static final double[] CORE = {0, 3.8, 5.5, 6.5};
    /** Halo radius by tier, editor units. */
    static final double[] HALO = {0, 7.0, 9.5, 11.0};
    /** Floor core radius by tier, GUI px, so the fit view still reads. */
    static final double[] FLOOR_CORE = {0, 2.5, 3.5, 4.5};
    static final double ROOT_CORE = 14, ROOT_HALO = 26, ROOT_DOT = 5, ROOT_FLOOR_CORE = 9;
    static final double TIER3_DOT = 2;
    static final double RING_OFFSET = 1.5, RING_WIDTH = 2.6, RING_GAP = 0.3;
    static final int RING_SLOTS = 10;
    /** Hit radius, editor units, and its floor in GUI px. */
    static final double HIT = 20, ROOT_HIT = 30, HIT_FLOOR = 8;
    /** Chord control point: midpoint pushed this much from the centre. */
    static final double CHORD_BULGE = 1.18;

    static int classColour(PerkTree tree, PerkTree.Perk p) {
        if (p.isRoot() || p.cls() == null) return GOLD;
        return tree.perkClass(p.cls()).map(PerkTree.PerkClass::colour).orElse(0xFF888888);
    }

    /** Scale a web radius by zoom with a GUI-px floor. */
    static double scaled(double units, double zoom, double floor) {
        return Math.max(units * zoom, floor);
    }

    static double coreRadius(PerkTree.Perk p, double zoom) {
        return p.isRoot() ? scaled(ROOT_CORE, zoom, ROOT_FLOOR_CORE) : scaled(CORE[p.tier()], zoom, FLOOR_CORE[p.tier()]);
    }

    /** Halo radius keeps the web ratio to the (possibly floored) core. */
    static double haloRadius(PerkTree.Perk p, double zoom) {
        double core = coreRadius(p, zoom);
        return p.isRoot() ? core * ROOT_HALO / ROOT_CORE : core * HALO[p.tier()] / CORE[p.tier()];
    }

    static double hitRadius(PerkTree.Perk p, double zoom) {
        return scaled(p.isRoot() ? ROOT_HIT : HIT, zoom, HIT_FLOOR);
    }
}
