package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.perks.PerkRegistry;
import games.brennan.dungeontrain.perks.PerkTree;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The floating details card beside the selected star: name, class, tier and cost, description, the
 * per-point line, a state line, one button (Unlock or Add point) and the linked names. Placed like
 * the web page's {@code placeCard}: below-right of the star, flipped left or above when out of room.
 * Text is literal and short; nothing here is a confirm dialog.
 */
@OnlyIn(Dist.CLIENT)
final class PerkCard {

    private static final int WIDE = 150, NARROW = 120, NARROW_SCREEN = 640;
    private static final int PAD = 6, LINE = 10, BUTTON_H = 16, MAX_DESC_LINES = 4;
    private static final long REFUSAL_MS = 2000, SHAKE_MS = 200;

    private final PerkTree tree;
    private final Font font;
    private final Consumer<String> unlock;
    private final Consumer<String> spend;

    private PerkTree.Perk perk;
    private int x, y, w, h;
    private Button button;
    private String refusalKey;
    private long refusedAt;

    PerkCard(PerkTree tree, Font font, Consumer<String> unlock, Consumer<String> spend) {
        this.tree = tree;
        this.font = font;
        this.unlock = unlock;
        this.spend = spend;
    }

    PerkTree.Perk perk() {
        return perk;
    }

    Button button() {
        return button;
    }

    void close() {
        perk = null;
        button = null;
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

    /** The first lines of the card as a hover tooltip. */
    List<Component> tooltip(PerkTree.Perk p, PerkMenuSnapshot snap) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(name(p)).withColor(SkyPalette.classColour(tree, p) & 0xFFFFFF));
        if (!p.isRoot()) lines.add(Component.translatable("gui.dungeontrain.perks.tier_cost", p.tier(), p.unlockCost()).withColor(SkyPalette.MUTED & 0xFFFFFF));
        lines.add(Component.literal(stateLine(p, snap)).withColor(SkyPalette.FG & 0xFFFFFF));
        return lines;
    }

    /** Lay the card out beside {@code p}'s star and (re)build its button; returns the button to add, or null. */
    Button open(PerkTree.Perk p, PerkMenuSnapshot snap, SkyView view, int screenW, int screenH, long now) {
        if (perk == null || !perk.id().equals(p.id())) refusalKey = null;
        perk = p;
        w = screenW < NARROW_SCREEN ? NARROW : WIDE;
        List<FormattedCharSequence> desc = descLines(p);
        boolean hasPoint = !p.point().isEmpty();
        boolean hasButton = !p.isRoot();
        h = PAD + LINE + 8 + LINE + desc.size() * LINE + (hasPoint ? LINE : 0) + 2 + LINE + (hasButton ? BUTTON_H + 4 : 0) + LINE + PAD;
        double sx = view.toScreenX(p.x(), screenW), sy = view.toScreenY(p.y(), screenH);
        double halo = SkyPalette.haloRadius(p, view.zoom());
        double gapX = Math.max(halo + 6, font.width(p.name()) / 2.0 + 6), gapY = halo + 14;
        int cx = (int) Math.round(sx + gapX), cy = (int) Math.round(sy + gapY);
        if (cx + w > screenW - 4) cx = (int) Math.round(sx - gapX - w);
        if (cx < 4) cx = 4;
        if (cy + h > screenH - 4) cy = (int) Math.round(sy - gapY - h);
        if (cy < SkyView.HEADER + 2) cy = SkyView.HEADER + 2;
        x = cx;
        y = cy;
        button = hasButton ? buildButton(p, snap) : null;
        return button;
    }

    private Button buildButton(PerkTree.Perk p, PerkMenuSnapshot snap) {
        PerkMenuSnapshot.StarState s = snap.stateOf(tree, p.id());
        int bx = x + PAD, by = y + h - PAD - LINE - 4 - BUTTON_H, bw = w - PAD * 2;
        if (s == PerkMenuSnapshot.StarState.LOCKED) return null;
        PerkRegistry.bootstrap();
        boolean built = PerkRegistry.isKnown(p.id());
        if (s == PerkMenuSnapshot.StarState.REACHABLE) {
            Button b = new DarkTintedButton(bx, by, bw, BUTTON_H, Component.translatable("gui.dungeontrain.perks.unlock"), bb -> unlock.accept(p.id()));
            b.active = built && snap.unlockPoints() >= p.unlockCost();
            if (!built) b.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.perks.not_built")));
            return b;
        }
        int max = snap.unlockLevel(p.id());
        if (snap.points(p.id()) >= max) {
            // Full: the button unlocks the perk once more, raising its limit by one point.
            Button raise = new DarkTintedButton(bx, by, bw, BUTTON_H,
                Component.translatable("gui.dungeontrain.perks.raise_limit", p.unlockCost()), bb -> unlock.accept(p.id()));
            raise.active = built && snap.canRaise(tree, p);
            if (!built) {
                raise.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.perks.not_built")));
            } else if (snap.unlockPoints() < p.unlockCost()) {
                raise.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.perks.refused.points")));
            } else if (!raise.active) {
                int next = max + 1;
                raise.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.perks.refused.gate",
                    games.brennan.dungeontrain.perks.PerkUnlocks.spentNeeded(next), next - 1)));
            }
            return raise;
        }
        Button b = new DarkTintedButton(bx, by, bw, BUTTON_H, Component.translatable("gui.dungeontrain.perks.add_point"), bb -> spend.accept(p.id()));
        if (!snap.canAffordRank()) {
            b.active = false;
            b.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.perks.eat_apple")
                .append("\n").append(Component.translatable("gui.dungeontrain.perks.rank_cost", snap.nextRankCost()))));
        } else if (!built) {
            b.active = false;
            b.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.perks.not_built")));
        } else if (snap.nextRankCost() > 1) {
            b.setTooltip(Tooltip.create(Component.translatable("gui.dungeontrain.perks.rank_cost", snap.nextRankCost())));
        }
        return b;
    }

    void render(GuiGraphics g, PerkMenuSnapshot snap, long now) {
        if (perk == null) return;
        PerkTree.Perk p = perk;
        int shake = refusalKey != null && now - refusedAt < SHAKE_MS ? (int) Math.round(2 * Math.sin((now - refusedAt) / 25.0)) : 0;
        g.pose().pushPose();
        g.pose().translate(shake, 0, 0);
        g.fill(x, y, x + w, y + h, SkyPalette.PANEL);
        g.renderOutline(x, y, w, h, SkyPalette.LINE);
        int tx = x + PAD, ty = y + PAD;
        g.drawString(font, name(p), tx, ty, SkyPalette.classColour(tree, p), true);
        ty += LINE;
        String cls = p.isRoot() ? "" : tree.perkClass(p.cls()).map(c -> c.name().toUpperCase(java.util.Locale.ROOT)).orElse("");
        g.pose().pushPose();
        g.pose().translate(tx, ty, 0);
        g.pose().scale(0.75F, 0.75F, 1F);
        g.drawString(font, cls, 0, 0, SkyPalette.MUTED, false);
        g.pose().popPose();
        ty += 8;
        if (!p.isRoot()) {
            g.drawString(font, Component.translatable("gui.dungeontrain.perks.tier_cost", p.tier(), p.unlockCost()), tx, ty, SkyPalette.FG, false);
        }
        ty += LINE;
        for (FormattedCharSequence line : descLines(p)) {
            g.drawString(font, line, tx, ty, SkyPalette.FG, false);
            ty += LINE;
        }
        if (!p.point().isEmpty()) {
            g.drawString(font, Component.translatable("gui.dungeontrain.perks.per_point", pointText(p)), tx, ty, SkyPalette.MUTED, false);
            ty += LINE;
        }
        ty += 2;
        String state = refusalKey != null && now - refusedAt < REFUSAL_MS ? Component.translatable(refusalKey).getString() : stateLine(p, snap);
        int stateColour = refusalKey != null && now - refusedAt < REFUSAL_MS ? 0xFFFFC9C9 : SkyPalette.FG;
        g.drawString(font, font.plainSubstrByWidth(state, w - PAD * 2), tx, ty, stateColour, false);
        if (button != null) button.setX(x + PAD + shake);
        g.drawString(font, font.plainSubstrByWidth(linksLine(p), w - PAD * 2), tx, y + h - PAD - LINE + 1, SkyPalette.MUTED, false);
        g.pose().popPose();
    }

    private List<FormattedCharSequence> descLines(PerkTree.Perk p) {
        List<FormattedCharSequence> lines = font.split(Component.literal(descText(p)), w - PAD * 2);
        return lines.size() > MAX_DESC_LINES ? lines.subList(0, MAX_DESC_LINES) : lines;
    }

    String name(PerkTree.Perk p) {
        return p.isRoot() ? p.name() : translated("perk.dungeontrain." + p.id() + ".name", p.name());
    }

    private String descText(PerkTree.Perk p) {
        return p.isRoot() ? Component.translatable("gui.dungeontrain.perks.the_start").getString()
            : translated("perk.dungeontrain." + p.id() + ".desc", p.desc());
    }

    private String pointText(PerkTree.Perk p) {
        return translated("perk.dungeontrain." + p.id() + ".point", p.point());
    }

    private static String translated(String key, String fallback) {
        return net.minecraft.client.resources.language.I18n.exists(key) ? net.minecraft.client.resources.language.I18n.get(key) : fallback;
    }

    String stateLine(PerkTree.Perk p, PerkMenuSnapshot snap) {
        if (p.isRoot()) return Component.translatable("gui.dungeontrain.perks.the_start").getString();
        return switch (snap.stateOf(tree, p.id())) {
            case LOCKED -> {
                String need = nearestReachableNeighbour(p, snap);
                yield need == null ? Component.translatable("gui.dungeontrain.perks.not_reachable").getString()
                    : Component.translatable("gui.dungeontrain.perks.needs", need).getString();
            }
            case REACHABLE -> snap.unlockPoints() >= p.unlockCost()
                ? Component.translatable("gui.dungeontrain.perks.locked_points", snap.unlockPoints()).getString()
                : Component.translatable("gui.dungeontrain.perks.locked_short", snap.unlockPoints(), p.unlockCost()).getString();
            case UNLOCKED -> Component.translatable("gui.dungeontrain.perks.unlocked_points", 0).getString();
            case ACTIVE -> Component.translatable("gui.dungeontrain.perks.points_of", snap.points(p.id()), snap.unlockLevel(p.id())).getString();
        };
    }

    private String nearestReachableNeighbour(PerkTree.Perk p, PerkMenuSnapshot snap) {
        for (PerkTree.Link l : tree.linksOf(p.id())) {
            if (l.kind() != PerkTree.LinkKind.REQ) continue;
            PerkTree.Perk q = tree.perk(l.other(p.id())).orElse(null);
            if (q != null && !q.isRoot()) return name(q);
        }
        return null;
    }

    private String linksLine(PerkTree.Perk p) {
        List<String> names = new ArrayList<>();
        for (PerkTree.Link l : tree.linksOf(p.id())) {
            tree.perk(l.other(p.id())).ifPresent(q -> names.add(name(q)));
        }
        return names.isEmpty() ? Component.translatable("gui.dungeontrain.perks.not_linked").getString()
            : Component.translatable("gui.dungeontrain.perks.links", String.join(", ", names)).getString();
    }
}
