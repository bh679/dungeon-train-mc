package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.PerkStats;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws the details bar's stat chips. A chip is its label, its value at the perk's points, and, while a
 * points block is hovered, an arrow and that block's value in gold. Hearts, armour and emeralds are drawn
 * as the game's own sprites, never as words; a label can place them too with the tokens {@code {heart}},
 * {@code {half_heart}}, {@code {armour}} and {@code {emerald}}.
 */
@OnlyIn(Dist.CLIENT)
final class StatChips {

    static final int ICON = 9;
    private static final int ICON_GAP = 1;
    /** More icons than this and the chip shows a count and one icon instead. */
    private static final int MAX_ICONS = 5;
    private static final String KEY = "gui.dungeontrain.perks.stat.";

    private static final ResourceLocation HEART_CONTAINER = ResourceLocation.withDefaultNamespace("hud/heart/container");
    private static final ResourceLocation HEART_FULL = ResourceLocation.withDefaultNamespace("hud/heart/full");
    private static final ResourceLocation HEART_HALF = ResourceLocation.withDefaultNamespace("hud/heart/half");
    private static final ResourceLocation ARMOUR_FULL = ResourceLocation.withDefaultNamespace("hud/armor_full");
    private static final ResourceLocation ARMOUR_HALF = ResourceLocation.withDefaultNamespace("hud/armor_half");

    private enum Icon { HEART, HALF_HEART, ARMOUR, HALF_ARMOUR, EMERALD }

    /** One run of a chip: text, or a sprite. */
    private record Piece(String text, Icon icon) {
        static Piece of(String text) {
            return new Piece(text, null);
        }

        static Piece of(Icon icon) {
            return new Piece(null, icon);
        }
    }

    private StatChips() {}

    /**
     * One chip per stat from {@code x} along {@code y}, stopping at the first that would pass {@code right}.
     * {@code preview} is the points a hovered block would give, or -1.
     */
    static void render(GuiGraphics g, Font font, List<PerkStats.Stat> stats, int points, int preview,
                       int x, int y, int right, int chipH, int pad, int gap, int bg, int fg, int gold, int a) {
        int cx = x;
        for (PerkStats.Stat s : stats) {
            List<Piece> now = new ArrayList<>(label(s.id()));
            now.add(Piece.of(" "));
            now.addAll(value(s.valueAt(points)));
            List<Piece> then = new ArrayList<>();
            if (preview >= 0 && preview != points) {
                List<Piece> next = value(s.valueAt(preview));
                // a stat the hovered point doesn't change (a Strength level within its band) shows no arrow
                if (!next.equals(value(s.valueAt(points)))) {
                    then.add(Piece.of(" → "));
                    then.addAll(next);
                }
            }
            int w = pad * 2 + width(font, now) + width(font, then);
            if (cx + w > right) break;
            g.fill(cx, y, cx + w, y + chipH, ((int) (a * 0.9) << 24) | (bg & 0xFFFFFF));
            int ty = y + (chipH - font.lineHeight) / 2 + 1;
            int iy = y + (chipH - ICON) / 2;
            int tx = draw(g, font, now, cx + pad, ty, iy, (a << 24) | (fg & 0xFFFFFF));
            draw(g, font, then, tx, ty, iy, (a << 24) | (gold & 0xFFFFFF));
            cx += w + gap;
        }
    }

    /** The stat's label, with any sprite tokens turned into sprites. */
    private static List<Piece> label(String statId) {
        String text = Component.translatable(KEY + statId).getString();
        List<Piece> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf('{', i);
            if (open < 0) {
                out.add(Piece.of(text.substring(i)));
                break;
            }
            int close = text.indexOf('}', open);
            Icon icon = close < 0 ? null : token(text.substring(open + 1, close));
            if (icon == null) {
                out.add(Piece.of(text.substring(i, open + 1)));
                i = open + 1;
                continue;
            }
            if (open > i) out.add(Piece.of(text.substring(i, open)));
            out.add(Piece.of(icon));
            i = close + 1;
        }
        return out;
    }

    private static Icon token(String name) {
        return switch (name) {
            case "heart" -> Icon.HEART;
            case "half_heart" -> Icon.HALF_HEART;
            case "armour" -> Icon.ARMOUR;
            case "emerald" -> Icon.EMERALD;
            default -> null;
        };
    }

    /** A value as text and sprites. */
    private static List<Piece> value(PerkStats.Value v) {
        if (v instanceof PerkStats.Text t) return List.of(Piece.of(t.text().getString()));
        PerkStats.Num n = (PerkStats.Num) v;
        String sign = n.signed() && n.value() > 0 ? "+" : "";
        return switch (n.unit()) {
            case HEARTS -> pair(sign, n.value(), Icon.HEART, Icon.HALF_HEART);
            case ARMOUR -> pair(sign, n.value(), Icon.ARMOUR, Icon.HALF_ARMOUR);
            case EMERALDS -> List.of(Piece.of(sign + (long) Math.floor(n.value()) + " "), Piece.of(Icon.EMERALD));
            case MULTIPLIER -> List.of(Piece.of(sign + Component.translatable(KEY + "unit." + n.unit().key(), number(n.value(), 100.0)).getString()));
            default -> List.of(Piece.of(sign + Component.translatable(KEY + "unit." + n.unit().key(), number(n.value())).getString()));
        };
    }

    /** Points shown two to an icon (hearts, armour): full icons then a half, or a count and one icon when long. */
    private static List<Piece> pair(String sign, double points, Icon full, Icon half) {
        int whole = (int) Math.floor(points / 2.0);
        boolean halfOne = points - whole * 2.0 >= 1.0;
        if (whole == 0 && !halfOne) return List.of(Piece.of(sign + "0"));
        List<Piece> out = new ArrayList<>();
        if (!sign.isEmpty()) out.add(Piece.of(sign));
        if (whole + (halfOne ? 1 : 0) > MAX_ICONS) {
            out.add(Piece.of(number(points / 2.0) + " × "));
            out.add(Piece.of(full));
            return out;
        }
        for (int i = 0; i < whole; i++) out.add(Piece.of(full));
        if (halfOne) out.add(Piece.of(half));
        return out;
    }

    /** Whole numbers plain, others to one place; minus as a plain hyphen. */
    static String number(double v) {
        return number(v, 10.0);
    }

    /** {@code v} rounded to 1/{@code scale}, without trailing zeros (a multiplier keeps 1.25). */
    static String number(double v, double scale) {
        double r = Math.round(v * scale) / scale;
        if (r == Math.rint(r)) return Long.toString((long) r);
        String s = String.format(Locale.ROOT, scale >= 100 ? "%.2f" : "%.1f", r);
        return s.endsWith("0") ? s.substring(0, s.length() - 1) : s;
    }

    private static int width(Font font, List<Piece> pieces) {
        int w = 0;
        for (Piece p : pieces) w += p.icon() != null ? ICON + ICON_GAP : font.width(p.text());
        return w;
    }

    private static int draw(GuiGraphics g, Font font, List<Piece> pieces, int x, int ty, int iy, int colour) {
        for (Piece p : pieces) {
            if (p.icon() == null) {
                g.drawString(font, p.text(), x, ty, colour, false);
                x += font.width(p.text());
                continue;
            }
            icon(g, p.icon(), x, iy);
            x += ICON + ICON_GAP;
        }
        return x;
    }

    private static void icon(GuiGraphics g, Icon icon, int x, int y) {
        switch (icon) {
            case HEART, HALF_HEART -> {
                g.blitSprite(HEART_CONTAINER, x, y, ICON, ICON);
                g.blitSprite(icon == Icon.HEART ? HEART_FULL : HEART_HALF, x, y, ICON, ICON);
            }
            case ARMOUR -> g.blitSprite(ARMOUR_FULL, x, y, ICON, ICON);
            case HALF_ARMOUR -> g.blitSprite(ARMOUR_HALF, x, y, ICON, ICON);
            case EMERALD -> {
                g.pose().pushPose();
                g.pose().translate(x, y, 0);
                g.pose().scale(ICON / 16F, ICON / 16F, 1F);
                g.renderItem(new ItemStack(Items.EMERALD), 0, 0);
                g.pose().popPose();
            }
        }
    }
}
