package games.brennan.dungeontrain.client;

import io.github.mortuusars.exposure.client.camera.CameraClient;
import io.github.mortuusars.exposure.client.camera.viewfinder.Viewfinder;
import io.github.mortuusars.exposure.client.camera.viewfinder.ViewfinderOverlay;
import io.github.mortuusars.exposure.util.Rect2f;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * A one-word key-cap hint under the camera viewfinder — {@code [F5] Selfie}, or {@code [F5] Front} while
 * in selfie mode — so players find Exposure's selfie toggle, which rides vanilla's Toggle Perspective
 * key and is otherwise only mentioned in a tooltip nobody sees. Drawn by
 * {@link games.brennan.dungeontrain.mixin.client.ViewfinderOverlaySelfieHintMixin}; follows the
 * player's binding, and hides with F1, with the camera controls open, or when the key is unbound.
 */
public final class ViewfinderSelfieHint {

    private static final Component SELFIE = Component.translatable("gui.dungeontrain.viewfinder.selfie");
    private static final Component FRONT = Component.translatable("gui.dungeontrain.viewfinder.front");

    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int KEY_TEXT_COLOR = 0xFF202020;
    private static final int KEY_FACE_COLOR = 0xFFE0E0E0;
    private static final int KEY_EDGE_COLOR = 0xFF808080;
    private static final int KEY_PAD_X = 3;
    private static final int KEY_PAD_Y = 2;
    private static final int GAP = 4;
    /** Space between the bottom of the viewfinder opening and the hint. */
    private static final int BELOW_OPENING = 6;
    /** Closest the hint may sit to the bottom of the screen. */
    private static final int BOTTOM_MARGIN = 4;

    private ViewfinderSelfieHint() {}

    public static void render(GuiGraphics graphics, ViewfinderOverlay overlay) {
        Minecraft mc = Minecraft.getInstance();
        KeyMapping key = mc.options.keyTogglePerspective;
        Viewfinder viewfinder = CameraClient.viewfinder();
        if (mc.options.hideGui || key.isUnbound() || viewfinder == null || viewfinder.controlsActive()) {
            return;
        }
        boolean selfie = mc.options.getCameraType() == CameraType.THIRD_PERSON_FRONT;
        Component label = selfie ? FRONT : SELFIE;
        Component keyName = key.getTranslatedKeyMessage();

        Font font = mc.font;
        int lineHeight = HudText.scaledLineHeight(font);
        int keyWidth = HudText.scaledWidth(font, keyName) + KEY_PAD_X * 2;
        int keyHeight = lineHeight + KEY_PAD_Y * 2;
        int totalWidth = keyWidth + GAP + HudText.scaledWidth(font, label);

        Rect2f opening = overlay.getOpening();
        int x = hintLeft(graphics, opening, totalWidth);
        int y = hintTop(graphics, opening, keyHeight);

        graphics.fill(x, y, x + keyWidth, y + keyHeight, KEY_EDGE_COLOR);
        graphics.fill(x + 1, y, x + keyWidth - 1, y + keyHeight - 1, KEY_FACE_COLOR);
        HudText.drawScaled(graphics, font, keyName, x + KEY_PAD_X, y + KEY_PAD_Y, KEY_TEXT_COLOR, false);
        HudText.drawScaled(graphics, font, label, x + keyWidth + GAP, y + KEY_PAD_Y, TEXT_COLOR, true);
    }

    /**
     * Right-aligned to the viewfinder opening — the bottom centre is taken by the instant camera's own
     * body tab — or bottom-centre of the screen if there is no opening yet.
     */
    private static int hintLeft(GuiGraphics graphics, Rect2f opening, int width) {
        if (opening == null) {
            return (graphics.guiWidth() - width) / 2;
        }
        return Math.max(0, Math.round(opening.x + opening.width) - width);
    }

    /** Just under the viewfinder opening, pulled up if that would run off the bottom of the screen. */
    private static int hintTop(GuiGraphics graphics, Rect2f opening, int height) {
        int lowest = graphics.guiHeight() - BOTTOM_MARGIN - height;
        if (opening == null) {
            return lowest;
        }
        return Math.min(Math.round(opening.y + opening.height) + BELOW_OPENING, lowest);
    }
}
