package games.brennan.dungeontrain.client.deathphotos;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Shared drawing for the photo strip and the fullscreen viewer. */
final class PhotoPainter {

    static final int CELL_BG = 0xFF14151A;
    static final int PLACEHOLDER_TEXT = 0xFF9A8F74;

    static final ResourceLocation PREV_ICON = icon("prev");
    static final ResourceLocation NEXT_ICON = icon("next");
    static final int BTN = 22;
    private static final int BTN_BG = 0xAA000000;
    static final int BTN_BORDER = 0x55FFFFFF;
    private static final int BTN_BORDER_HOVER = 0xFFE6D6B0;

    static ResourceLocation icon(String name) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/" + name);
    }

    /** A {@link #BTN}-square dark button with a 16px sprite; returns its rect {@code {x, y, w, h}}. */
    static int[] iconButton(GuiGraphics g, ResourceLocation sprite, int x, int y, int border, int mx, int my) {
        boolean hover = mx >= x && mx < x + BTN && my >= y && my < y + BTN;
        g.fill(x, y, x + BTN, y + BTN, BTN_BG);
        drawBorder(g, x, y, BTN, BTN, hover ? BTN_BORDER_HOVER : border);
        g.blitSprite(sprite, x + (BTN - 16) / 2, y + (BTN - 16) / 2, 16, 16);
        return new int[] {x, y, BTN, BTN};
    }

    static boolean has(int[] r, double mx, double my) {
        return r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private PhotoPainter() {}

    /**
     * Contain-fit {@code photo} into the box (the whole frame visible, centred). A photo that isn't
     * ready draws a dark cell with "developing…" / "photo lost" instead. Returns the drawn rect as
     * {@code {x, y, w, h}}.
     */
    static int[] drawContain(GuiGraphics g, Font font, DeathPhoto photo, int x, int y, int w, int h) {
        DeathPhoto.Status status = photo.status();
        if (status != DeathPhoto.Status.READY || photo.texture() == null) {
            g.fill(x, y, x + w, y + h, CELL_BG);
            Component label = Component.translatable(status == DeathPhoto.Status.MISSING
                    ? "gui.dungeontrain.death.photos.missing"
                    : "gui.dungeontrain.death.photos.developing");
            g.drawCenteredString(font, label, x + w / 2, y + (h - font.lineHeight) / 2, PLACEHOLDER_TEXT);
            return new int[] {x, y, w, h};
        }
        float imgAspect = photo.aspect();
        float boxAspect = (float) w / Math.max(1, h);
        int dw, dh;
        if (boxAspect > imgAspect) { // box wider than photo → match height
            dh = h;
            dw = Math.round(h * imgAspect);
        } else {                     // match width
            dw = w;
            dh = Math.round(w / imgAspect);
        }
        int dx = x + (w - dw) / 2;
        int dy = y + (h - dh) / 2;
        g.blit(photo.texture(), dx, dy, dw, dh, 0.0f, 0.0f,
                photo.width(), photo.height(), photo.width(), photo.height());
        return new int[] {dx, dy, dw, dh};
    }

    static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }
}
