package games.brennan.dungeontrain.client.localization.edit;

import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A picture of a vanilla button for the translation preview, its label scrolling side to side when it
 * is too long — exactly as {@code AbstractWidget#renderScrollingString} moves it.
 *
 * <p>Vanilla clips the scrolling label with a scissor in screen coordinates, which ignores the pose
 * the preview scales its frame with, so a too-long label inside the frame was clipped away entirely.
 * This one maps its clip box through the current pose first. Never added as a widget: it cannot be
 * clicked.</p>
 */
final class PreviewButton extends Button {

    /** Vanilla's inset of the label from each side of a button. */
    private static final int LABEL_INSET = 2;

    PreviewButton(int x, int y, int w, int h, Component label) {
        super(x, y, w, h, label, b -> { }, DEFAULT_NARRATION);
    }

    /** Drawn resting — an off-screen mouse never hovers it — so it looks as players first see it. */
    void draw(GuiGraphics g) {
        render(g, -1, -1, 0);
    }

    @Override
    public void renderString(GuiGraphics g, Font font, int color) {
        int minX = getX() + LABEL_INSET;
        int maxX = getX() + getWidth() - LABEL_INSET;
        int minY = getY();
        int maxY = getY() + getHeight();
        Component text = getMessage();
        int textWidth = font.width(text);
        int y = (minY + maxY - 9) / 2 + 1;
        int room = maxX - minX;
        if (textWidth <= room) {
            int cx = Mth.clamp((minX + maxX) / 2, minX + textWidth / 2, maxX - textWidth / 2);
            g.drawCenteredString(font, text, cx, y, color);
            return;
        }
        int over = textWidth - room;
        double seconds = Util.getMillis() / 1000.0;
        double period = Math.max(over * 0.5, 3.0);
        double t = Math.sin((Math.PI / 2) * Math.cos((Math.PI * 2) * seconds / period)) / 2.0 + 0.5;
        double offset = Mth.lerp(t, 0.0, over);
        scissorThroughPose(g, minX, minY, maxX, maxY);
        g.drawString(font, text, minX - (int) offset, y, color);
        g.disableScissor();
    }

    /** {@link GuiGraphics#enableScissor} for a box in the current pose's coordinates. */
    private static void scissorThroughPose(GuiGraphics g, int x0, int y0, int x1, int y1) {
        Matrix4f pose = g.pose().last().pose();
        Vector3f a = pose.transformPosition(x0, y0, 0, new Vector3f());
        Vector3f b = pose.transformPosition(x1, y1, 0, new Vector3f());
        g.enableScissor(Mth.floor(Math.min(a.x, b.x)), Mth.floor(Math.min(a.y, b.y)),
            Mth.ceil(Math.max(a.x, b.x)), Mth.ceil(Math.max(a.y, b.y)));
    }
}
