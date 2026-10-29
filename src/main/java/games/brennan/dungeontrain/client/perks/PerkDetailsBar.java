package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkRegistry;
import games.brennan.dungeontrain.perks.PerkStats;
import games.brennan.dungeontrain.perks.PerkTree;
import games.brennan.dungeontrain.perks.PerkUnlocks;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The permanent details bar along the bottom of the perks menu: the hovered perk, or the one clicked
 * to keep it there. Name and class on the left with a stripe in the class colour, the description in
 * the middle, and on the right where the perk stands ({@code Points P / L}) with Unlock and Activate
 * buttons. Disabled buttons say why in their tooltip; the hint line under the points says what to do.
 */
@OnlyIn(Dist.CLIENT)
final class PerkDetailsBar {

    static final int HEIGHT = 54;
    private static final int PAD = 8, LINE = 10, BUTTON_W = 34, BUTTON_H = 16, GAP = 4, STRIPE = 3;
    private static final int MIN_LEFT = 90, DOT_W = 8;
    private static final int SEG_H = 5, GRAPH_H = 7;
    private static final int CHIP_H = 11, CHIP_PAD = 4, CHIP_GAP = 4, CHIP_BG = 0x1B1F36;
    private static final float CLASS_SCALE = 0.75F;
    private static final int CARD_H = 18, CARD_GAP = 3, CARD_ROWS = 2, CARD_PAD = 4, CARD_MIN_W = 40;
    private static final int BLOCK_W = 6, BLOCK_H = 3;
    /** Deep indigo, a shade off the sky so the bar reads as its own surface. */
    private static final int BG = 0xF20D0B2A;
    private static final int EDGE = 0xFF3A3F6A;
    private static final String KEY = "gui.dungeontrain.perks.bar.";

    private final PerkTree tree;
    private final Font font;
    private final PerkCard text;
    private final Consumer<PerkTree.Perk> unlock;
    private final Consumer<PerkTree.Perk> activate;

    private int top, width;
    private PerkTree.Perk perk;
    private boolean locked;
    private String classShown;
    private String refusalKey;
    private String refusedFor;
    private long refusedAt;
    private static final long REFUSAL_MS = 2000;
    private UnlockTab unlockButton;
    /** Why the Unlock tab can't be pressed, said in the bar under it; null when it can. */
    private Component unlockReason;
    private Button activateButton;
    /** The death screen's menu: Unlock only, no Activate and no Active Points. */
    private boolean unlockOnly;
    private String builtFor = "";
    private PointsGraph graph;
    private int graphX, graphY, graphW, graphHover = -1;

    PerkDetailsBar(PerkTree tree, Font font, PerkCard text, Consumer<PerkTree.Perk> unlock, Consumer<PerkTree.Perk> activate) {
        this.tree = tree;
        this.font = font;
        this.text = text;
        this.unlock = unlock;
        this.activate = activate;
    }

    /** The screen re-initialised and dropped its widgets: rebuild the buttons on the next update. */
    void invalidate() {
        builtFor = "";
    }

    /** Show why an action on {@code perkId} was refused, in place of its status line, for a moment. */
    void refused(String perkId, String key, long now) {
        refusedFor = perkId;
        refusalKey = key;
        refusedAt = now;
    }

    int top() {
        return top;
    }

    boolean contains(double mx, double my) {
        return my >= top;
    }

    /** Unlock only: the Activate button and every Active Points line drop out. */
    void unlockOnly(boolean on) {
        if (on == unlockOnly) return;
        unlockOnly = on;
        builtFor = "";
    }

    /** The buttons, rebuilt when the perk or its state changes; empty while nothing is shown. */
    List<Button> buttons() {
        if (unlockButton == null) return List.of();
        return activateButton == null ? List.of(unlockButton) : List.of(unlockButton, activateButton);
    }

    /** How many buttons sit at the right of the bar: Activate, unless unlocking only (Unlock is the tab above). */
    private int buttonCount() {
        return unlockOnly ? 0 : 1;
    }

    /** Over the Unlock tab standing on the bar's top edge. */
    boolean tabContains(double mx, double my) {
        // by bounds, not isMouseOver, which ignores a greyed tab
        Button t = unlockButton;
        return t != null && mx >= t.getX() && mx < t.getX() + t.getWidth() && my >= t.getY() && my < t.getY() + t.getHeight();
    }

    /**
     * Lay out for this frame. Returns true when the buttons were rebuilt (the screen swaps its widgets).
     */
    boolean update(int screenW, int screenH, PerkTree.Perk shown, boolean isLocked, String hoveredClass, PerkMenuSnapshot snap) {
        width = screenW;
        top = screenH - HEIGHT;
        perk = shown;
        locked = isLocked;
        classShown = shown == null ? hoveredClass : null;
        String key = shown == null ? "" : shown.id() + "|" + isLocked + "|" + snap.unlockLevel(shown.id()) + "|" + snap.points(shown.id())
            + "|" + snap.unlockPoints() + "|" + snap.activePoints() + "|" + screenW + "x" + screenH;
        if (key.equals(builtFor)) return false;
        builtFor = key;
        if (shown == null || shown.isRoot()) {
            unlockButton = null;
            activateButton = null;
            unlockReason = null;
            return true;
        }
        // Unlock: the tab centred on the bar's top edge, naming the level it buys; why not, under it in the bar
        unlockButton = UnlockTab.centred(font, screenW / 2, top, Component.translatable(KEY + "unlock"),
            snap.unlockLevel(shown.id()) + 1, shown.unlockCost(), b -> unlock.accept(shown));
        Component why = unlockBlocked(shown, snap);
        if (!isLocked) {
            // a hovered perk is shown, never bought: an outline when it could be unlocked once
            // selected, grey when it couldn't; Activate stays away
            unlockButton.look(why == null ? UnlockTab.Look.AVAILABLE : UnlockTab.Look.BLOCKED);
            unlockReason = null;
            activateButton = null;
            return true;
        }
        unlockReason = why;
        unlockButton.look(why == null ? UnlockTab.Look.READY : UnlockTab.Look.BLOCKED);
        if (unlockOnly) {
            activateButton = null;
            return true;
        }
        int bx = screenW - PAD - BUTTON_W, by = top + (HEIGHT - BUTTON_H) / 2;
        activateButton = new CostButton(bx, by, BUTTON_W, BUTTON_H, CostButton.Currency.APPLE, snap.nextRankCost(),
            Component.translatable(KEY + "activate"), b -> activate.accept(shown));
        Component activateWhy = activateBlocked(shown, snap);
        activateButton.active = activateWhy == null;
        if (activateWhy != null) activateButton.setTooltip(Tooltip.create(activateWhy));
        return true;
    }

    /** Why Unlock can't be pressed now, or null when it can. Unlocks a locked perk, or again once full. */
    private Component unlockBlocked(PerkTree.Perk p, PerkMenuSnapshot s) {
        PerkRegistry.bootstrap();
        if (!PerkRegistry.isKnown(p.id())) return Component.translatable("gui.dungeontrain.perks.not_built");
        int level = s.unlockLevel(p.id());
        if (level <= 0 && !tree.reachable(p.id(), s::isUnlocked)) return Component.translatable("gui.dungeontrain.perks.card.needs_path");
        // points are gone at death, so the death screen's menu raises a level without filling it first
        if (!unlockOnly && level > 0 && s.points(p.id()) < level) return Component.translatable(KEY + "fill_first");
        int spent = s.unlockSpent(tree);
        if (level > 0 && !PerkUnlocks.allowed(level + 1, spent)) {
            return Component.translatable("gui.dungeontrain.perks.card.gate", PerkCard.ap(PerkUnlocks.spentNeeded(level + 1) - spent),
                PerkUnlocks.levelLimit(spent));
        }
        if (s.unlockPoints() < p.unlockCost()) return Component.translatable("gui.dungeontrain.perks.card.you_hold", s.unlockPoints());
        return null;
    }

    /** Why Activate can't be pressed now, or null when it can. Adds a point while below the unlock level. */
    private Component activateBlocked(PerkTree.Perk p, PerkMenuSnapshot s) {
        PerkRegistry.bootstrap();
        if (!PerkRegistry.isKnown(p.id())) return Component.translatable("gui.dungeontrain.perks.not_built");
        int level = s.unlockLevel(p.id());
        if (level <= 0) return Component.translatable("gui.dungeontrain.perks.refused.locked");
        if (s.points(p.id()) >= level) return Component.translatable(KEY + "full");
        if (!s.canAffordRank()) return Component.translatable("gui.dungeontrain.perks.card.next_cost", s.nextRankCost(), s.activePoints());
        return null;
    }

    /** Width of the left column: wide enough for every line in it, never narrower than {@link #MIN_LEFT}. */
    private int leftColumn(String... lines) {
        int w = MIN_LEFT;
        for (String l : lines) w = Math.max(w, font.width(l) + 12);
        return w;
    }

    /** A thin vertical rule between the bar's sections. */
    private void divider(GuiGraphics g, int x, int a) {
        g.fill(x, top + PAD - 2, x + 1, top + HEIGHT - PAD + 2, ((int) (a * 0.8) << 24) | (EDGE & 0xFFFFFF));
    }

    /**
     * A class hovered in the Classes list. Left column: its name over one segment per perk. Right of a divider:
     * one card per perk in two rows, filling columns left to right, each showing its points as blocks.
     * What doesn't fit ends in a "+N" card.
     */
    private void renderClass(GuiGraphics g, PerkMenuSnapshot snap, int a) {
        PerkTree.PerkClass c = tree.perkClass(classShown).orElse(null);
        if (c == null) return;
        List<PerkTree.Perk> perks = tree.perksOf(c.id());
        g.fill(0, top + 1, STRIPE, top + HEIGHT, (a << 24) | (c.colour() & 0xFFFFFF));
        int x = PAD + STRIPE, y = top + PAD;
        g.drawString(font, c.name(), x, y, (a << 24) | (c.colour() & 0xFFFFFF), true);
        int column = leftColumn(c.name());
        renderSegments(g, snap, perks, c.colour(), x, y + LINE + 4, column - 12, a);
        int split = x + column;
        divider(g, split, a);

        int left = split + 8, right = width - PAD;
        int colW = 0;
        for (PerkTree.Perk p : perks) colW = Math.max(colW, cardWidth(p, snap));
        colW = Math.min(colW, Math.max(CARD_MIN_W, right - left));
        int cols = Math.max(1, (right - left + CARD_GAP) / (colW + CARD_GAP));
        int fits = cols * CARD_ROWS;
        int y0 = top + 1 + (HEIGHT - 1 - CARD_ROWS * CARD_H - (CARD_ROWS - 1) * CARD_GAP) / 2;
        for (int i = 0; i < perks.size(); i++) {
            int cx = left + i / CARD_ROWS * (colW + CARD_GAP), cy = y0 + i % CARD_ROWS * (CARD_H + CARD_GAP);
            if (i == fits - 1 && perks.size() > fits) {
                card(g, cx, cy, colW, null, snap, 0, "+" + (perks.size() - i), a);
                break;
            }
            card(g, cx, cy, colW, perks.get(i), snap, c.colour(), null, a);
        }
    }

    /**
     * The class's perks as one graph: active ones filled in the class colour, unlocked ones with no point
     * outlined, locked ones dark ({@link PointsGraph} turns it into a bar when the blocks get too thin).
     */
    private void renderSegments(GuiGraphics g, PerkMenuSnapshot snap, List<PerkTree.Perk> perks, int colour,
                                int x, int y, int maxW, int a) {
        int active = 0, unlocked = 0;
        for (PerkTree.Perk p : perks) {
            if (snap.points(p.id()) > 0) active++;
            else if (snap.unlockLevel(p.id()) > 0) unlocked++;
        }
        new PointsGraph(active, unlocked, perks.size() - active - unlocked, perks.size())
            .render(g, font, x, y, maxW, SEG_H, colour, a / 255.0, -1, 0.75F);
    }

    /** Wide enough for the name, or for its point blocks if they run longer. */
    private int cardWidth(PerkTree.Perk p, PerkMenuSnapshot snap) {
        int level = snap.unlockLevel(p.id());
        int blocks = Math.min(level, PointsGraph.MAX_SEGMENTS) * (BLOCK_W + 1) - 1;
        return CARD_PAD * 2 + Math.max(font.width(text.name(p)), blocks);
    }

    /**
     * One perk's card, {@code w} wide in its column: the name, and under it one block per point it can
     * hold (filled per point, outlined while empty), the way a star's ring shows them. Edged and faintly
     * filled in the class colour once unlocked; dark and muted while locked. A null perk draws {@code more}.
     */
    private void card(GuiGraphics g, int x, int y, int w, PerkTree.Perk p, PerkMenuSnapshot snap, int colour,
                      String more, int a) {
        int level = p == null ? 0 : snap.unlockLevel(p.id());
        boolean on = level > 0;
        int fill = on ? SkyDraw.withAlpha(colour, 0.14 * a / 255.0) : ((int) (a * 0.6) << 24) | (SkyPalette.PANEL & 0xFFFFFF);
        int edge = on ? SkyDraw.withAlpha(colour, 0.85 * a / 255.0) : ((int) (a * 0.9) << 24) | (EDGE & 0xFFFFFF);
        int h = CARD_H;
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        g.fill(x + 1, y, x + w - 1, y + 1, edge);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, edge);
        g.fill(x, y + 1, x + 1, y + h - 1, edge);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, edge);
        int tx = x + CARD_PAD, ty = y + 2;
        if (p == null) {
            g.drawString(font, more, tx, y + (h - font.lineHeight) / 2 + 1, (a << 24) | (SkyPalette.MUTED & 0xFFFFFF), false);
            return;
        }
        String name = font.plainSubstrByWidth(text.name(p), w - CARD_PAD * 2);
        g.drawString(font, name, tx, ty, (a << 24) | ((on ? SkyPalette.FG : SkyPalette.MUTED) & 0xFFFFFF), false);
        if (!on) return;
        int points = snap.points(p.id()), room = w - CARD_PAD * 2;
        int shown = Math.min(level, PointsGraph.MAX_SEGMENTS);
        int gw = Math.min(room, shown * (BLOCK_W + PointsGraph.GAP) - PointsGraph.GAP + (level > shown ? PointsGraph.PLUS_W + 1 : 0));
        new PointsGraph(points, level - points, 0, level)
            .render(g, font, tx, y + h - 2 - BLOCK_H, Math.max(gw, room / 2), BLOCK_H, colour, a / 255.0, -1, 0.5F);
    }

    /**
     * The Engine hovered or kept: how many points are active against how many the unlocked perks can
     * hold, and what each currency does, each beside its icon.
     */
    private void renderEngine(GuiGraphics g, PerkMenuSnapshot snap, int a) {
        g.fill(0, top + 1, STRIPE, top + HEIGHT, (a << 24) | (SkyPalette.GOLD & 0xFFFFFF));
        int holds = 0;
        for (int level : snap.unlocks().values()) holds += Math.max(0, level);
        int x = PAD + STRIPE, y = top + PAD - 1, icon = 8, tx = x + icon + 5;
        if (unlockOnly) {
            g.blit(PerkCounters.ADVANCEMENT_ICON, x, y + LINE + 1, icon, icon, 0, 0, PerkCounters.ICON_TEX, PerkCounters.ICON_TEX,
                PerkCounters.ICON_TEX, PerkCounters.ICON_TEX);
            g.drawString(font, Component.translatable(KEY + "engine.advancements"), tx, y + LINE + 2,
                ((int) (a * 0.85) << 24) | (SkyPalette.FG & 0xFFFFFF), false);
            return;
        }
        g.drawString(font, Component.translatable(KEY + "engine.active", snap.ranksBought(), holds), x, y,
            (a << 24) | (SkyPalette.FG & 0xFFFFFF), false);
        int y2 = y + LINE + 2, y3 = y2 + LINE + 1;
        g.blit(PerkCounters.ADVANCEMENT_ICON, x, y2 - 1, icon, icon, 0, 0, PerkCounters.ICON_TEX, PerkCounters.ICON_TEX,
            PerkCounters.ICON_TEX, PerkCounters.ICON_TEX);
        g.drawString(font, Component.translatable(KEY + "engine.advancements"), tx, y2, ((int) (a * 0.85) << 24) | (SkyPalette.FG & 0xFFFFFF), false);
        g.pose().pushPose();
        g.pose().translate(x, y3 - 1, 0);
        g.pose().scale(0.5F, 0.5F, 1F);
        g.renderItem(new net.minecraft.world.item.ItemStack(games.brennan.dungeontrain.registry.ModItems.COSMIC_APPLE.get()), 0, 0);
        g.pose().popPose();
        g.drawString(font, Component.translatable(KEY + "engine.apples"), tx, y3, ((int) (a * 0.85) << 24) | (SkyPalette.FG & 0xFFFFFF), false);
    }

    void render(GuiGraphics g, PerkMenuSnapshot snap, double fade) {
        int a = (int) (fade * 255);
        for (Button b : buttons()) b.setAlpha((float) fade);
        g.fill(0, top, width, top + HEIGHT, (Math.min(a, BG >>> 24) << 24) | (BG & 0xFFFFFF));
        g.fill(0, top, width, top + 1, (a << 24) | (EDGE & 0xFFFFFF));
        if (perk == null && classShown != null) {
            renderClass(g, snap, a);
            return;
        }
        if (perk == null) {
            Component hint = Component.translatable(KEY + "hint");
            g.drawString(font, hint, (width - font.width(hint)) / 2, top + (HEIGHT - font.lineHeight) / 2 + 1,
                ((int) (a * 0.8) << 24) | (SkyPalette.MUTED & 0xFFFFFF), false);
            return;
        }
        PerkTree.Perk p = perk;
        if (p.isRoot()) {
            renderEngine(g, snap, a);
            return;
        }
        int colour = SkyPalette.classColour(tree, p);
        g.fill(0, top + 1, STRIPE, top + HEIGHT, (a << 24) | (colour & 0xFFFFFF));
        if (unlockReason != null) {
            // why the Unlock tab is greyed, centred under it at the foot of the bar
            g.drawString(font, unlockReason, (width - font.width(unlockReason)) / 2, top + HEIGHT - font.lineHeight - 3,
                ((int) (a * 0.9) << 24) | (SkyPalette.MUTED & 0xFFFFFF), false);
        }

        // top row: the name, the points graph across the row, then the class and tier
        int x = PAD + STRIPE, y = top + 5;
        int contentRight = width - PAD - (BUTTON_W + GAP) * buttonCount() - 6;
        String name = text.name(p);
        g.drawString(font, name, x, y, (a << 24) | (colour & 0xFFFFFF), true);
        String cls = tree.perkClass(p.cls()).map(PerkTree.PerkClass::name).orElse("").toUpperCase(Locale.ROOT)
            + " · " + Component.translatable(KEY + "tier", p.tier()).getString().toUpperCase(Locale.ROOT) + (locked ? "  •" : "");
        int clsW = (int) Math.ceil(font.width(cls) * CLASS_SCALE);
        g.pose().pushPose();
        g.pose().translate(contentRight - clsW, y + 2, 0);
        g.pose().scale(CLASS_SCALE, CLASS_SCALE, 1F);
        g.drawString(font, cls, 0, 0, (a << 24) | (SkyPalette.MUTED & 0xFFFFFF), false);
        g.pose().popPose();
        graphX = x + font.width(name) + 8;
        graphY = y;
        graphW = Math.max(0, contentRight - clsW - 8 - graphX);
        graph = perkGraph(p, snap);
        if (graphW > 0) graph.render(g, font, graphX, graphY, graphW, GRAPH_H, colour, a / 255.0, graphHover, 1F);

        // below: the description, then the per-point line; a refusal takes the last line, in red;
        // the perk's key stats as chips along the bottom
        int dy = y + LINE + 2, mw = contentRight - x;
        List<PerkStats.Stat> stats = PerkStats.of(p.id());
        int chipY = top + HEIGHT - 4 - CHIP_H;
        int max = ((stats.isEmpty() ? top + HEIGHT : chipY) - 1 - dy) / LINE;
        boolean refused = refusalKey != null && p.id().equals(refusedFor) && net.minecraft.Util.getMillis() - refusedAt < REFUSAL_MS;
        int room = refused ? max - 1 : max;
        int shown = 0;
        for (FormattedCharSequence l : font.split(Component.literal(text.descText(p)), mw)) {
            if (shown >= room) break;
            g.drawString(font, l, x, dy + shown++ * LINE, (a << 24) | (SkyPalette.FG & 0xFFFFFF), false);
        }
        if (!p.point().isEmpty()) {
            for (FormattedCharSequence l : font.split(Component.translatable("gui.dungeontrain.perks.per_point", text.pointText(p)), mw)) {
                if (shown >= room) break;
                g.drawString(font, l, x, dy + shown++ * LINE, (a << 24) | (SkyPalette.MUTED & 0xFFFFFF), false);
            }
        }
        if (refused) {
            String why = font.plainSubstrByWidth(Component.translatable(refusalKey).getString(), mw);
            g.drawString(font, why, x, dy + (max - 1) * LINE, (a << 24) | 0xFFC9C9, false);
        }
        if (!stats.isEmpty()) renderStats(g, stats, snap.points(p.id()), x, chipY, contentRight, a);
    }

    /**
     * One chip per stat: its name and its value at the perk's points now; with a graph block hovered,
     * an arrow and the value that block's point would give, in gold.
     */
    private void renderStats(GuiGraphics g, List<PerkStats.Stat> stats, int points, int x, int y, int right, int a) {
        int preview = graphHover >= 0 && graph != null && graphHover < graph.layout(graphW).segments() ? graphHover + 1 : -1;
        int cx = x;
        for (PerkStats.Stat s : stats) {
            String now = Component.translatable("gui.dungeontrain.perks.stat." + s.id()).getString() + " " + statValue(s, points);
            String then = preview >= 0 && preview != points ? " → " + statValue(s, preview) : "";
            int w = CHIP_PAD * 2 + font.width(now) + font.width(then);
            if (cx + w > right) break;
            g.fill(cx, y, cx + w, y + CHIP_H, ((int) (a * 0.9) << 24) | (CHIP_BG & 0xFFFFFF));
            int ty = y + (CHIP_H - font.lineHeight) / 2 + 1;
            g.drawString(font, now, cx + CHIP_PAD, ty, (a << 24) | (SkyPalette.FG & 0xFFFFFF), false);
            if (!then.isEmpty()) {
                g.drawString(font, then, cx + CHIP_PAD + font.width(now), ty, (a << 24) | (SkyPalette.GOLD & 0xFFFFFF), false);
            }
            cx += w + CHIP_GAP;
        }
    }

    /** A stat's value at {@code points}: whole numbers plain, others to one place, in its unit. */
    private static String statValue(PerkStats.Stat s, int points) {
        double v = s.at(points);
        String n = v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.1f", v);
        return s.unit().isEmpty() ? n : Component.translatable("gui.dungeontrain.perks.stat.unit." + s.unit(), n).getString();
    }

    /** A perk's points, its empty room, and one dark block for the next unlock. */
    private static PointsGraph perkGraph(PerkTree.Perk p, PerkMenuSnapshot snap) {
        int level = snap.unlockLevel(p.id()), points = Math.min(snap.points(p.id()), level);
        return new PointsGraph(points, level - points, 1, level);
    }

    /** The mouse moved: light the graph block under it. */
    void mouse(double mx, double my) {
        // lit only where a click acts: a block that takes a point, never one that would need unlocking
        boolean acts = !unlockOnly && overGraph(mx, my) && graph.at(graphW, mx - graphX) != PointsGraph.Kind.LOCKED;
        graphHover = acts ? graph.indexAt(graphW, mx - graphX) : -1;
    }

    private boolean overGraph(double mx, double my) {
        return graph != null && perk != null && !perk.isRoot() && graphW > 0
            && my >= graphY - 2 && my < graphY + GRAPH_H + 2 && mx >= graphX && mx < graphX + graphW;
    }

    /**
     * A click in the bar: on the graph, a filled or outlined block adds a point (never while unlocking
     * only). Unlocking is the Unlock tab's alone: a dark block does nothing. Returns true when it acted.
     */
    boolean click(double mx, double my) {
        if (unlockOnly || !overGraph(mx, my)) return false;
        PointsGraph.Kind k = graph.at(graphW, mx - graphX);
        if (k == PointsGraph.Kind.LOCKED || k == null && (!graph.layout(graphW).bar() || graph.open == 0)) return false;
        activate.accept(perk);
        return true;
    }
}
