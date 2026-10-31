package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * The two counters at the bottom right (the web page's test counters, without the click to add): the
 * gold advancement frame with the Advancement Points held, and a cosmic apple with the Active Points
 * held. Hovering one shows what it is and how it is earned; a refusal for want of points shakes the
 * matching counter red.
 */
@OnlyIn(Dist.CLIENT)
final class PerkCounters {

    enum Kind { UNLOCK, ACTIVE }

    /** The web page's Advancement Points icon (a gold advancement frame), 26 px square. */
    static final ResourceLocation ADVANCEMENT_ICON =
        ResourceLocation.fromNamespaceAndPath(games.brennan.dungeontrain.DungeonTrain.MOD_ID, "textures/gui/perks/advancement_points.png");
    static final int ICON_TEX = 26;
    private static final int ICON = 16, PAD_X = 5, PAD_Y = 3, GAP = 6, MARGIN = 8;
    private static final long SHAKE_MS = 400;
    private static final int DANGER = 0xFFD9534F;

    private final Font font;
    private final int[] bx = new int[2], bw = new int[2];
    private int by, bh;
    private final long[] shookAt = {-1, -1};
    /** The death screen's menu: the Advancement Points counter alone. */
    private boolean unlockOnly;

    PerkCounters(Font font) {
        this.font = font;
    }

    void unlockOnly(boolean on) {
        unlockOnly = on;
    }

    private boolean shown(Kind k) {
        return k == Kind.UNLOCK || !unlockOnly;
    }

    void shake(Kind k, long now) {
        shookAt[k.ordinal()] = now;
    }

    /** Lay out at the bottom right; returns the counters' top edge (the card keeps above it). */
    int layout(int screenW, int screenH, int unlock, int active) {
        bh = ICON + PAD_Y * 2;
        by = screenH - MARGIN - bh;
        int[] counts = {unlock, active};
        int x = screenW - MARGIN;
        for (int i = 1; i >= 0; i--) {
            if (!shown(Kind.values()[i])) {
                bw[i] = 0;
                continue;
            }
            bw[i] = PAD_X + ICON + 4 + font.width(String.valueOf(counts[i])) + PAD_X;
            x -= bw[i];
            bx[i] = x;
            x -= GAP;
        }
        return by - 6;
    }

    Kind at(double mx, double my) {
        if (my < by || my > by + bh) return null;
        for (Kind k : Kind.values()) {
            int i = k.ordinal();
            if (!shown(k)) continue;
            if (mx >= bx[i] && mx <= bx[i] + bw[i]) return k;
        }
        return null;
    }

    void render(GuiGraphics g, int unlock, int active, long now, double fade) {
        int[] counts = {unlock, active};
        int a = (int) (fade * 255);
        for (Kind k : Kind.values()) {
            if (!shown(k)) continue;
            int i = k.ordinal();
            long age = shookAt[i] < 0 ? -1 : now - shookAt[i];
            boolean nope = age >= 0 && age < SHAKE_MS;
            int dx = nope ? (int) Math.round(6 * Math.sin(age / (double) SHAKE_MS * Math.PI * 4)) : 0;
            int x = bx[i] + dx;
            g.fill(x, by, x + bw[i], by + bh, (Math.min(a, 0xE6) << 24) | (SkyPalette.PANEL & 0xFFFFFF));
            g.renderOutline(x, by, bw[i], bh, nope ? DANGER : (a << 24) | (SkyPalette.LINE & 0xFFFFFF));
            int ix = x + PAD_X, iy = by + PAD_Y;
            if (k == Kind.UNLOCK) {
                g.blit(ADVANCEMENT_ICON, ix - 1, iy - 1, ICON + 2, ICON + 2, 0, 0, ICON_TEX, ICON_TEX, ICON_TEX, ICON_TEX);
            } else {
                g.renderItem(new ItemStack(ModItems.COSMIC_APPLE.get()), ix, iy);
            }
            g.drawString(font, String.valueOf(counts[i]), ix + ICON + 4, by + (bh - font.lineHeight) / 2 + 1, (a << 24) | (SkyPalette.FG & 0xFFFFFF), true);
        }
    }

    /** The hover tip: title, how it is earned. */
    static List<Component> tooltip(Kind k) {
        String base = "gui.dungeontrain.perks.counter." + (k == Kind.UNLOCK ? "unlock" : "active");
        if (k == Kind.UNLOCK) {
            return List.of(Component.translatable(base + ".title").withColor(SkyPalette.FG & 0xFFFFFF),
                Component.translatable(base + ".body").withColor(SkyPalette.MUTED & 0xFFFFFF),
                Component.translatable(base + ".click").withColor(SkyPalette.GOLD & 0xFFFFFF));
        }
        return List.of(Component.translatable(base + ".title").withColor(SkyPalette.FG & 0xFFFFFF),
            Component.translatable(base + ".body").withColor(SkyPalette.MUTED & 0xFFFFFF));
    }
}
