package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.perks.PerkRegistry;
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

    static final int HEIGHT = 46;
    private static final int PAD = 8, LINE = 10, BUTTON_W = 60, BUTTON_H = 16, GAP = 4, STRIPE = 3;
    private static final int MIN_LEFT = 90, DOT_W = 8;
    private static final int PILL_H = 12, PILL_GAP = 3, PILL_PITCH = PILL_H + 2, PILL_PAD = 4;
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
    private Button unlockButton;
    private Button activateButton;
    private String builtFor = "";

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

    /** The two buttons, rebuilt when the perk or its state changes; null while nothing is shown. */
    List<Button> buttons() {
        return unlockButton == null ? List.of() : List.of(unlockButton, activateButton);
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
        String key = shown == null ? "" : shown.id() + "|" + snap.unlockLevel(shown.id()) + "|" + snap.points(shown.id())
            + "|" + snap.unlockPoints() + "|" + snap.activePoints() + "|" + screenW + "x" + screenH;
        if (key.equals(builtFor)) return false;
        builtFor = key;
        if (shown == null || shown.isRoot()) {
            unlockButton = null;
            activateButton = null;
            return true;
        }
        int bx = screenW - PAD - BUTTON_W * 2 - GAP, by = top + HEIGHT - PAD - BUTTON_H;
        unlockButton = new DarkTintedButton(bx, by, BUTTON_W, BUTTON_H, Component.translatable(KEY + "unlock"), b -> unlock.accept(shown));
        activateButton = new DarkTintedButton(bx + BUTTON_W + GAP, by, BUTTON_W, BUTTON_H, Component.translatable(KEY + "activate"),
            b -> activate.accept(shown));
        Component unlockWhy = unlockBlocked(shown, snap);
        Component activateWhy = activateBlocked(shown, snap);
        unlockButton.active = unlockWhy == null;
        activateButton.active = activateWhy == null;
        if (unlockWhy != null) unlockButton.setTooltip(Tooltip.create(unlockWhy));
        if (activateWhy != null) activateButton.setTooltip(Tooltip.create(activateWhy));
        return true;
    }

    /** Why Unlock can't be pressed now, or null when it can. Unlocks a locked perk, or again once full. */
    private Component unlockBlocked(PerkTree.Perk p, PerkMenuSnapshot s) {
        PerkRegistry.bootstrap();
        if (!PerkRegistry.isKnown(p.id())) return Component.translatable("gui.dungeontrain.perks.not_built");
        int level = s.unlockLevel(p.id());
        if (level <= 0 && !tree.reachable(p.id(), s::isUnlocked)) return Component.translatable("gui.dungeontrain.perks.card.needs_path");
        if (level > 0 && s.points(p.id()) < level) return Component.translatable(KEY + "fill_first");
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
     * A class hovered in the Classes list. Left column: its name and "X/Y perks". Right of a divider:
     * one pill per perk, flowing left to right and wrapping, lit in the class colour once unlocked (with
     * its points), muted while locked. What doesn't fit ends in a "+N" pill.
     */
    private void renderClass(GuiGraphics g, PerkMenuSnapshot snap, int a) {
        PerkTree.PerkClass c = tree.perkClass(classShown).orElse(null);
        if (c == null) return;
        List<PerkTree.Perk> perks = tree.perksOf(c.id());
        g.fill(0, top + 1, STRIPE, top + HEIGHT, (a << 24) | (c.colour() & 0xFFFFFF));
        int x = PAD + STRIPE, y = top + PAD;
        long unlocked = perks.stream().filter(p -> snap.unlockLevel(p.id()) > 0).count();
        Component count = Component.translatable(KEY + "class_progress", unlocked, perks.size());
        g.drawString(font, c.name(), x, y, (a << 24) | (c.colour() & 0xFFFFFF), true);
        g.drawString(font, count, x, y + LINE + 2, ((int) (a * 0.8) << 24) | (SkyPalette.MUTED & 0xFFFFFF), false);
        int split = x + leftColumn(c.name(), count.getString());
        divider(g, split, a);

        int left = split + 8, right = width - PAD;
        int rows = Math.max(1, (HEIGHT - 2) / PILL_PITCH);
        int px = left, py = top + 1 + (HEIGHT - 1 - rows * PILL_PITCH + PILL_GAP) / 2, row = 0;
        for (int i = 0; i < perks.size(); i++) {
            PerkTree.Perk p = perks.get(i);
            int level = snap.unlockLevel(p.id());
            String name = text.name(p), pts = level > 0 ? snap.points(p.id()) + "/" + level : "";
            int w = pillWidth(name, pts);
            String more = "+" + (perks.size() - i);
            int moreW = font.width(more) + PILL_PAD * 2;
            if (px > left && px + w > right) {
                if (row == rows - 1) {
                    if (px + moreW <= right) pill(g, px, py, more, "", 0, false, a);
                    break;
                }
                row++;
                px = left;
                py += PILL_PITCH;
            }
            boolean moreAfter = i < perks.size() - 1;
            if (row == rows - 1 && moreAfter && px + w + PILL_GAP + font.width("+" + (perks.size() - i - 1)) + PILL_PAD * 2 > right) {
                pill(g, px, py, more, "", 0, false, a);
                break;
            }
            pill(g, px, py, name, pts, c.colour(), level > 0, a);
            px += w + PILL_GAP;
        }
    }

    private int pillWidth(String name, String pts) {
        return PILL_PAD + DOT_W + font.width(name) + (pts.isEmpty() ? 0 : 4 + font.width(pts)) + PILL_PAD;
    }

    /**
     * One perk's pill: a rounded box, filled faintly and edged in the class colour once unlocked, dark and
     * muted while locked; the dot, the name, then its points. {@code colour} 0 draws a plain "+N" pill.
     */
    private void pill(GuiGraphics g, int x, int y, String name, String pts, int colour, boolean on, int a) {
        int w = colour == 0 ? font.width(name) + PILL_PAD * 2 : pillWidth(name, pts);
        int fill = on ? SkyDraw.withAlpha(colour, 0.22 * a / 255.0) : ((int) (a * 0.6) << 24) | (SkyPalette.PANEL & 0xFFFFFF);
        int edge = on ? SkyDraw.withAlpha(colour, 0.85 * a / 255.0) : ((int) (a * 0.9) << 24) | (EDGE & 0xFFFFFF);
        int h = PILL_H;
        g.fill(x + 1, y, x + w - 1, y + h, fill);
        g.fill(x, y + 1, x + 1, y + h - 1, fill);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, fill);
        g.fill(x + 1, y, x + w - 1, y + 1, edge);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, edge);
        g.fill(x, y + 1, x + 1, y + h - 1, edge);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, edge);
        int ty = y + (h - font.lineHeight) / 2 + 1, tx = x + PILL_PAD;
        if (colour != 0) {
            SkyDraw.disc(g, tx + 2.5, y + h / 2.0, 2.0, SkyDraw.withAlpha(on ? colour : SkyPalette.MUTED, (on ? 1.0 : 0.6) * a / 255.0));
            tx += DOT_W;
        }
        g.drawString(font, name, tx, ty, (a << 24) | ((on ? SkyPalette.FG : SkyPalette.MUTED) & 0xFFFFFF), false);
        if (!pts.isEmpty()) {
            g.drawString(font, pts, tx + font.width(name) + 4, ty, ((int) (a * 0.85) << 24) | (colour & 0xFFFFFF), false);
        }
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

        // left: name and class (and a lock mark when clicked to stay)
        int x = PAD + STRIPE, y = top + PAD;
        String name = text.name(p);
        g.drawString(font, name, x, y, (a << 24) | (colour & 0xFFFFFF), true);
        String cls = p.isRoot() ? Component.translatable("gui.dungeontrain.perks.card.start").getString()
            : tree.perkClass(p.cls()).map(PerkTree.PerkClass::name).orElse("");
        if (locked) cls = cls + "  •";
        g.pose().pushPose();
        g.pose().translate(x, y + LINE + 1, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, cls.toUpperCase(Locale.ROOT), 0, 0, (a << 24) | (SkyPalette.MUTED & 0xFFFFFF), false);
        g.pose().popPose();
        if (!p.isRoot()) {
            g.drawString(font, Component.translatable("gui.dungeontrain.perks.bar.tier", p.tier()), x, y + LINE * 2 + 2,
                ((int) (a * 0.8) << 24) | (SkyPalette.MUTED & 0xFFFFFF), false);
        }
        String tierLine = p.isRoot() ? "" : Component.translatable("gui.dungeontrain.perks.bar.tier", p.tier()).getString();
        int leftW = leftColumn(name, cls.toUpperCase(Locale.ROOT), tierLine);
        divider(g, x + leftW, a);

        // right: where the perk stands, right-aligned above the buttons; it may reach left past them
        int rightW = BUTTON_W * 2 + GAP;
        int right = width - PAD;
        List<Component> lines = text.testLines(p, snap);
        int statusW = 0;
        boolean refused = refusalKey != null && p.id().equals(refusedFor) && net.minecraft.Util.getMillis() - refusedAt < REFUSAL_MS;
        if (refused || !lines.isEmpty()) {
            String status = font.plainSubstrByWidth(refused ? Component.translatable(refusalKey).getString() : lines.get(0).getString(),
                Math.max(rightW, width / 3));
            statusW = font.width(status);
            g.drawString(font, status, right - statusW, y, (a << 24) | ((refused ? 0xFFFFC9C9 : SkyPalette.FG) & 0xFFFFFF), false);
        }
        if (lines.size() > 1 && p.isRoot()) {
            String hint = font.plainSubstrByWidth(lines.get(1).getString(), Math.max(rightW, width / 3));
            g.drawString(font, hint, right - font.width(hint), y + LINE, (a << 24) | (SkyPalette.MUTED & 0xFFFFFF), false);
            statusW = Math.max(statusW, font.width(hint));
        }
        int rx = right - Math.max(rightW, statusW);

        // middle: description, then the per-point line, wrapped into the space between
        divider(g, rx - 8, a);
        int mx = x + leftW + 10, mw = rx - 16 - mx;
        if (mw > 40) {
            int ty = y;
            int max = (HEIGHT - PAD * 2) / LINE;
            int shown = 0;
            for (FormattedCharSequence l : font.split(Component.literal(text.descText(p)), mw)) {
                if (shown++ >= max) break;
                g.drawString(font, l, mx, ty, (a << 24) | (SkyPalette.FG & 0xFFFFFF), false);
                ty += LINE;
            }
            if (!p.isRoot() && !p.point().isEmpty() && shown < max) {
                for (FormattedCharSequence l : font.split(Component.translatable("gui.dungeontrain.perks.per_point", text.pointText(p)), mw)) {
                    if (shown++ >= max) break;
                    g.drawString(font, l, mx, ty, (a << 24) | (SkyPalette.MUTED & 0xFFFFFF), false);
                    ty += LINE;
                }
            }
        }
    }
}
