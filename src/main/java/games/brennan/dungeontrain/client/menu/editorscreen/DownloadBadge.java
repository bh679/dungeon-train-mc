package games.brennan.dungeontrain.client.menu.editorscreen;

import net.minecraft.client.gui.GuiGraphics;

/**
 * A small bobbing down-arrow over a tray: "this is downloading right now".
 *
 * <p>Drawn in pixels rather than as a glyph so it reads the same at every GUI scale and needs no
 * texture. The arrow bobs one pixel twice a second, so a tile that is busy looks busy.</p>
 */
final class DownloadBadge {

    /** Edge of the square badge, the same as the star it sits opposite. */
    static final int SIZE = 9;

    private static final int BG = 0xD0000000;
    private static final int ARROW = 0xFF55DDFF;
    private static final int TRAY = 0xFFFFFFFF;

    private DownloadBadge() {}

    static void draw(GuiGraphics g, int x, int y) {
        int bob = (int) ((System.currentTimeMillis() / 250L) % 2L);
        g.fill(x, y, x + SIZE, y + SIZE, BG);
        g.fill(x + 4, y + 1 + bob, x + 5, y + 4 + bob, ARROW);   // shaft
        g.fill(x + 2, y + 4 + bob, x + 7, y + 5 + bob, ARROW);   // head
        g.fill(x + 3, y + 5 + bob, x + 6, y + 6 + bob, ARROW);
        g.fill(x + 4, y + 6 + bob, x + 5, y + 7 + bob, ARROW);
        g.fill(x + 1, y + 8, x + 8, y + 9, TRAY);                // tray
    }
}
