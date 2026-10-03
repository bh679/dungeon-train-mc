package games.brennan.dungeontrain.client.deathphotos;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Fullscreen view of one photo from the photo page, layered over the whole death screen.
 *
 * <p>The photo fills the screen contain-fit; a save icon sits top-right (a check once saved),
 * arrows step through the set when there is more than one, and a status line reports the saved
 * file. Closes on Esc, right-click, or a click anywhere off the photo and its controls.</p>
 */
public final class PhotoViewerOverlay {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation SAVE_ICON = icon("save");
    private static final ResourceLocation SAVED_ICON = icon("review_accepted");
    private static final ResourceLocation PREV_ICON = icon("prev");
    private static final ResourceLocation NEXT_ICON = icon("next");

    private static final int BACKDROP = 0xF2050506;
    private static final int BTN = 22;
    private static final int BTN_BG = 0xAA000000;
    private static final int BTN_BORDER = 0x55FFFFFF;
    private static final int BTN_BORDER_HOVER = 0xFFE6D6B0;
    private static final int SAVED_BORDER = 0xFF3C6B41;
    private static final int COUNTER = 0xFF9A8F74;
    private static final int STATUS_OK = 0xFF7FAE84;
    private static final int STATUS_ERR = 0xFFFF5555;
    private static final int PHOTO_BORDER = 0x33FFFFFF;
    private static final float Z = 500f; // above item icons and the page's widgets

    private final Set<DeathPhoto> saved = Collections.newSetFromMap(new IdentityHashMap<>());
    private int index = -1;
    private Component status = Component.empty();
    private int statusColor = STATUS_OK;

    // Hit rects from the last frame, {x, y, w, h}.
    private int[] photoRect, saveRect, prevRect, nextRect;

    private static ResourceLocation icon(String name) {
        return ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "icon/" + name);
    }

    public boolean isOpen() {
        return index >= 0;
    }

    public void open(int photoIndex) {
        index = photoIndex;
        status = Component.empty();
    }

    public void close() {
        index = -1;
        photoRect = saveRect = prevRect = nextRect = null;
    }

    public void render(GuiGraphics g, Font font, List<DeathPhoto> photos, int w, int h, int mouseX, int mouseY) {
        if (!isOpen()) return;
        if (index >= photos.size()) { close(); return; }
        DeathPhoto photo = photos.get(index);
        boolean multi = photos.size() > 1;

        g.pose().pushPose();
        g.pose().translate(0f, 0f, Z);
        g.fill(0, 0, w, h, BACKDROP);

        int sideRoom = multi ? 40 : 16;
        int top = 34, bottom = h - 26;
        photoRect = PhotoPainter.drawContain(g, font, photo, sideRoom, top, w - 2 * sideRoom, bottom - top);
        PhotoPainter.drawBorder(g, photoRect[0] - 1, photoRect[1] - 1, photoRect[2] + 2, photoRect[3] + 2, PHOTO_BORDER);

        if (multi) {
            g.drawCenteredString(font, (index + 1) + " / " + photos.size(), w / 2, 12, COUNTER);
            prevRect = button(g, PREV_ICON, 9, h / 2 - BTN / 2, BTN_BORDER, mouseX, mouseY);
            nextRect = button(g, NEXT_ICON, w - 9 - BTN, h / 2 - BTN / 2, BTN_BORDER, mouseX, mouseY);
        } else {
            prevRect = nextRect = null;
        }

        boolean isSaved = saved.contains(photo);
        boolean canSave = photo.status() == DeathPhoto.Status.READY;
        saveRect = button(g, isSaved ? SAVED_ICON : SAVE_ICON, w - 8 - BTN, 6,
                isSaved ? SAVED_BORDER : BTN_BORDER, canSave ? mouseX : -1, mouseY);

        if (!status.getString().isEmpty()) {
            g.drawCenteredString(font, status, w / 2, h - 17, statusColor);
        }
        if (has(saveRect, mouseX, mouseY)) {
            g.renderTooltip(font, Component.translatable(isSaved
                    ? "gui.dungeontrain.death.gallery.saved"
                    : "gui.dungeontrain.death.photos.save_tip"), mouseX, mouseY);
        }
        g.pose().popPose();
    }

    private int[] button(GuiGraphics g, ResourceLocation sprite, int x, int y, int border, int mx, int my) {
        boolean hover = mx >= x && mx < x + BTN && my >= y && my < y + BTN;
        g.fill(x, y, x + BTN, y + BTN, BTN_BG);
        PhotoPainter.drawBorder(g, x, y, BTN, BTN, hover ? BTN_BORDER_HOVER : border);
        g.blitSprite(sprite, x + (BTN - 16) / 2, y + (BTN - 16) / 2, 16, 16);
        return new int[] {x, y, BTN, BTN};
    }

    /** Mouse press while open. Always consumes the event, so nothing beneath reacts. */
    public boolean mouseClicked(List<DeathPhoto> photos, double mx, double my, int button) {
        if (!isOpen()) return false;
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) { close(); return true; }
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        if (has(saveRect, mx, my)) { save(photos); return true; }
        if (has(prevRect, mx, my)) { step(photos, -1); return true; }
        if (has(nextRect, mx, my)) { step(photos, 1); return true; }
        if (!has(photoRect, mx, my)) close();
        return true;
    }

    /** Key press while open: Esc closes, ←/→ step. Consumes every key so the page can't react. */
    public boolean keyPressed(List<DeathPhoto> photos, int keyCode) {
        if (!isOpen()) return false;
        switch (keyCode) {
            case GLFW.GLFW_KEY_ESCAPE -> close();
            case GLFW.GLFW_KEY_LEFT -> step(photos, -1);
            case GLFW.GLFW_KEY_RIGHT -> step(photos, 1);
            default -> { }
        }
        return true;
    }

    private void step(List<DeathPhoto> photos, int dir) {
        if (photos.size() < 2) return;
        index = Math.floorMod(index + dir, photos.size());
        status = Component.empty();
    }

    private void save(List<DeathPhoto> photos) {
        if (index < 0 || index >= photos.size()) return;
        DeathPhoto photo = photos.get(index);
        if (saved.contains(photo) || photo.status() != DeathPhoto.Status.READY) return;
        try {
            Path p = photo.save();
            saved.add(photo);
            status = Component.translatable("gui.dungeontrain.death.gallery.status_saved", p.getFileName().toString());
            statusColor = STATUS_OK;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Failed to save death-screen photo {}", photo, e);
            status = Component.translatable("gui.dungeontrain.death.gallery.status_failed");
            statusColor = STATUS_ERR;
        }
    }

    private static boolean has(int[] r, double mx, double my) {
        return r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }
}
