package games.brennan.dungeontrain.client.perks;

import games.brennan.dungeontrain.perks.Perks;
import games.brennan.dungeontrain.perks.effects.archivist.ArchivistPerks;
import games.brennan.dungeontrain.perks.effects.archivist.follow.FavouriteAuthor;
import games.brennan.dungeontrain.perks.effects.archivist.follow.FollowsClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Favourite Author's "Following N (M active)" button on the perk card, after the stat chips once the
 * perk is unlocked. Opens the Followed Authors menu ({@link FollowedAuthorsScreen}).
 */
final class FollowingChip {

    /** Where the button was drawn. */
    record Hit(int x0, int y0, int x1, int y1) {
        boolean contains(double mx, double my) {
            return mx >= x0 && mx < x1 && my >= y0 && my < y1;
        }
    }

    private static final int BG = 0x2A2F55, BG_HOVER = 0x3C4378, EDGE = 0x556088;

    private FollowingChip() {}

    static boolean shows(String perkId, int unlockLevel) {
        return ArchivistPerks.FAV_AUTHOR.equals(perkId) && unlockLevel > 0;
    }

    static Component label() {
        Minecraft mc = Minecraft.getInstance();
        int rank = mc.player == null ? 0 : Perks.rank(mc.player, ArchivistPerks.FAV_AUTHOR);
        int n = FollowsClientState.followCount();
        return Component.translatable("gui.dungeontrain.follows.button", n, FavouriteAuthor.activeCount(n, rank));
    }

    /** Draws the button from {@code x}; null when there is no room before {@code right}. */
    static Hit render(GuiGraphics g, Font font, int x, int y, int right, int h, int pad, int a, double mx, double my) {
        Component label = label();
        int w = font.width(label) + pad * 2;
        if (x + w > right) return null;
        Hit hit = new Hit(x, y, x + w, y + h);
        int bg = hit.contains(mx, my) ? BG_HOVER : BG;
        g.fill(x, y, x + w, y + h, ((int) (a * 0.95) << 24) | bg);
        g.renderOutline(x, y, w, h, (a << 24) | EDGE);
        g.drawString(font, label, x + pad, y + (h - font.lineHeight) / 2 + 1, (a << 24) | (SkyPalette.FG & 0xFFFFFF), false);
        return hit;
    }

    static void open() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new FollowedAuthorsScreen(mc.screen));
    }
}
