package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.narrative.PluralRules;
import games.brennan.dungeontrain.perks.PerkRegistry;
import games.brennan.dungeontrain.perks.PerkTree;
import games.brennan.dungeontrain.perks.PerkUnlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The card beside the hovered (or keyboard-selected) star, as the web page's test mode shows it:
 * name, class, description, the per-point line, the linked perks, then where the perk stands and what
 * a click on the star does ({@code perks.js testControls}). There are no buttons: clicking the star
 * acts. Placed like the web's {@code placeCard}: below-right of the star, flipped left or above when
 * out of room, clear of the Classes list and the counters.
 */
@OnlyIn(Dist.CLIENT)
final class PerkCard {

    private static final int WIDE = 160, NARROW = 124, NARROW_SCREEN = 640;
    private static final int PAD = 6, LINE = 10, MAX_DESC_LINES = 4;
    private static final long REFUSAL_MS = 2000, SHAKE_MS = 200;
    private static final String KEY = "gui.dungeontrain.perks.card.";

    private record Line(FormattedCharSequence text, int colour) {}

    private final PerkTree tree;
    private final Font font;

    private PerkTree.Perk perk;
    private int x, y, w, h;
    private List<Line> body = List.of();
    private int dividerAt;
    private String refusalKey;
    private long refusedAt;

    PerkCard(PerkTree tree, Font font) {
        this.tree = tree;
        this.font = font;
    }

    PerkTree.Perk perk() {
        return perk;
    }

    void close() {
        perk = null;
        refusalKey = null;
    }

    void refused(String perkId, String key, long now) {
        if (perk != null && perk.id().equals(perkId)) {
            refusalKey = key;
            refusedAt = now;
        }
    }

    boolean contains(double mx, double my) {
        return perk != null && mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /**
     * Lay the card out beside {@code p}'s star in the drawn tree.
     *
     * @param drawn   the tree as drawn (positions after the class layout)
     * @param right   the card's right limit (left edge of the Classes list)
     * @param bottom  the card's bottom limit (top of the counters)
     */
    void open(PerkTree.Perk p, PerkTree drawn, PerkMenuSnapshot snap, SkyView view, int screenW, int screenH,
              int right, int bottom) {
        if (perk == null || !perk.id().equals(p.id())) refusalKey = null;
        perk = p;
        w = screenW < NARROW_SCREEN ? NARROW : WIDE;
        int inner = w - PAD * 2;
        List<Line> lines = new ArrayList<>();
        for (FormattedCharSequence l : descLines(p, inner)) lines.add(new Line(l, SkyPalette.FG));
        if (!p.isRoot() && !p.point().isEmpty()) {
            for (FormattedCharSequence l : font.split(Component.translatable("gui.dungeontrain.perks.per_point", pointText(p)), inner)) {
                lines.add(new Line(l, SkyPalette.MUTED));
            }
        }
        for (FormattedCharSequence l : font.split(Component.literal(linksLine(p)), inner)) lines.add(new Line(l, SkyPalette.MUTED));
        dividerAt = lines.size();
        for (Component c : testLines(p, snap)) {
            boolean hint = c.getStyle().getColor() == null;
            for (FormattedCharSequence l : font.split(c, inner)) lines.add(new Line(l, hint ? SkyPalette.MUTED : SkyPalette.FG));
        }
        body = List.copyOf(lines);
        h = PAD + LINE + 8 + body.size() * LINE + 6 + PAD;

        PerkTree.Perk at = drawn.perk(p.id()).orElse(p);
        double sx = view.toScreenX(at.x(), screenW), sy = view.toScreenY(at.y(), screenH);
        double halo = SkyPalette.haloRadius(p, view.zoom());
        double gapX = Math.max(halo + 6, font.width(p.name()) / 2.0 + 6), gapY = halo + 14;
        int cx = (int) Math.round(sx + gapX), cy = (int) Math.round(sy + gapY);
        if (cx + w > right) cx = (int) Math.round(sx - gapX - w);
        if (cx < 4) cx = 4;
        if (cy + h > bottom) cy = (int) Math.round(sy - gapY - h);
        if (cy + h > bottom) cy = bottom - h;
        if (cy < SkyView.HEADER + 2) cy = SkyView.HEADER + 2;
        x = cx;
        y = cy;
    }

    void render(GuiGraphics g, long now) {
        if (perk == null) return;
        PerkTree.Perk p = perk;
        int shake = refusalKey != null && now - refusedAt < SHAKE_MS ? (int) Math.round(2 * Math.sin((now - refusedAt) / 25.0)) : 0;
        g.pose().pushPose();
        g.pose().translate(shake, 0, 200); // above the sky's names in depth, not only in draw order
        g.fill(x, y, x + w, y + h, SkyPalette.PANEL);
        g.renderOutline(x, y, w, h, SkyPalette.LINE);
        int tx = x + PAD, ty = y + PAD;
        g.drawString(font, name(p), tx, ty, SkyPalette.classColour(tree, p), true);
        ty += LINE;
        String cls = p.isRoot() ? Component.translatable("gui.dungeontrain.perks.card.start").getString()
            : tree.perkClass(p.cls()).map(c -> c.name()).orElse("");
        g.pose().pushPose();
        g.pose().translate(tx, ty, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, cls.toUpperCase(Locale.ROOT), 0, 0, SkyPalette.MUTED, false);
        g.pose().popPose();
        ty += 8;
        boolean refused = refusalKey != null && now - refusedAt < REFUSAL_MS;
        for (int i = 0; i < body.size(); i++) {
            if (i == dividerAt) {
                ty += 3;
                g.fill(x + PAD, ty, x + w - PAD, ty + 1, SkyPalette.LINE);
                ty += 4;
                if (refused) {
                    g.drawString(font, font.plainSubstrByWidth(Component.translatable(refusalKey).getString(), w - PAD * 2), tx, ty, 0xFFFFC9C9, false);
                    ty += LINE;
                    continue;
                }
            }
            Line line = body.get(i);
            g.drawString(font, line.text(), tx, ty, line.colour(), false);
            ty += LINE;
        }
        g.pose().popPose();
    }

    /** Where the perk stands and what a click does (web {@code testControls}). Hints carry no colour. */
    List<Component> testLines(PerkTree.Perk p, PerkMenuSnapshot snap) {
        List<Component> out = new ArrayList<>();
        if (p.isRoot()) {
            out.add(state(Component.translatable(KEY + "always_active")));
            return out;
        }
        PerkRegistry.bootstrap();
        int cost = p.unlockCost();
        int level = snap.unlockLevel(p.id()), pts = snap.points(p.id());
        if (level <= 0) {
            out.add(state(Component.translatable(KEY + "locked", p.tier(), ap(cost))));
            if (!PerkRegistry.isBuilt(p.id())) out.add(Component.translatable("gui.dungeontrain.perks.not_built"));
            else if (!tree.reachableNow(p.id(), snap::isUnlocked)) out.add(Component.translatable(KEY + "needs_path"));
            else if (snap.unlockPoints() < cost) out.add(Component.translatable(KEY + "you_hold", snap.unlockPoints()));
            else out.add(Component.translatable(KEY + "click_unlock"));
            return out;
        }
        out.add(state(Component.translatable(KEY + "points", pts, level)));
        if (pts < level) {
            int next = snap.nextRankCost();
            out.add(snap.activePoints() < next
                ? Component.translatable(KEY + "next_cost", next, snap.activePoints())
                : Component.translatable(KEY + "click_add", next));
            return out;
        }
        int spent = snap.unlockSpent(tree);
        if (!PerkUnlocks.allowed(level + 1, spent)) {
            out.add(Component.translatable(KEY + "gate", ap(PerkUnlocks.spentNeeded(level + 1) - spent), PerkUnlocks.levelLimit(spent)));
        } else if (snap.unlockPoints() < cost) {
            out.add(Component.translatable(KEY + "again_cost", ap(cost), snap.unlockPoints()));
        } else {
            out.add(Component.translatable(KEY + "click_again", ap(cost)));
        }
        return out;
    }

    private static Component state(Component c) {
        return c.copy().withColor(SkyPalette.FG & 0xFFFFFF);
    }

    /** "1 Advancement Point" / "5 Advancement Points", in the reader's plural forms. */
    static Component ap(int n) {
        String locale = Minecraft.getInstance().getLanguageManager().getSelected();
        return PluralRules.clause(locale, "gui.dungeontrain.perks.ap", n);
    }

    private List<FormattedCharSequence> descLines(PerkTree.Perk p, int inner) {
        List<FormattedCharSequence> lines = font.split(Component.literal(descText(p)), inner);
        return lines.size() > MAX_DESC_LINES ? lines.subList(0, MAX_DESC_LINES) : lines;
    }

    String name(PerkTree.Perk p) {
        return p.isRoot() ? p.name() : translated("perk.dungeontrain." + p.id() + ".name", p.name());
    }

    String descText(PerkTree.Perk p) {
        return p.isRoot() ? Component.translatable("gui.dungeontrain.perks.the_start").getString()
            : translated("perk.dungeontrain." + p.id() + ".desc", p.desc());
    }

    String pointText(PerkTree.Perk p) {
        return translated("perk.dungeontrain." + p.id() + ".point", p.point());
    }

    /**
     * Perk text as written: it takes no arguments, so it is read straight from the language, never
     * formatted ({@code I18n.get} would read a "%" in "X% slower" as a format code and print a
     * format error).
     */
    private static String translated(String key, String fallback) {
        return I18n.exists(key) ? net.minecraft.locale.Language.getInstance().getOrDefault(key) : fallback;
    }

    /** The linked perks' names joined with a middle dot, as the web card's hint line. */
    private String linksLine(PerkTree.Perk p) {
        List<String> names = new ArrayList<>();
        for (PerkTree.Link l : tree.linksOf(p.id())) {
            tree.perk(l.other(p.id())).ifPresent(q -> names.add(name(q)));
        }
        return names.isEmpty() ? Component.translatable("gui.dungeontrain.perks.not_linked").getString() : String.join(" · ", names);
    }
}
