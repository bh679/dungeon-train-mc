package games.brennan.dungeontrain.compat.jade;

import games.brennan.dungeontrain.client.live.LivePillSlot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import snownee.jade.api.Accessor;
import snownee.jade.api.callback.JadeAfterRenderCallback;
import snownee.jade.api.callback.JadeBeforeRenderCallback;
import snownee.jade.api.ui.IBoxElement;
import snownee.jade.api.ui.TooltipRect;

/**
 * While this player is live, moves Jade's tooltip down under the stream's name pill
 * ({@link LivePillSlot}) instead of letting it cover the pill. Jade's rect is in GUI pixels.
 *
 * <p>Jade chases {@code rect} toward where it wants the box each frame, then calls
 * {@link #beforeRender} and draws at {@code rect}. The shift is added here and taken off again in
 * {@link #afterRender}, so the next frame's chase starts from Jade's own position — the offset
 * never compounds, and Jade's slide animation still runs when the box changes size.</p>
 */
enum LiveEyeJadeClient implements JadeBeforeRenderCallback, JadeAfterRenderCallback {
    INSTANCE;

    /** The shift still applied to {@link #shifted}, or 0. Render thread. */
    private int applied;
    private Rect2i shifted;

    @Override
    public boolean beforeRender(IBoxElement box, TooltipRect rect, GuiGraphics graphics, Accessor<?> accessor) {
        undo(); // a cancelled render last frame never reached afterRender
        Rect2i r = rect.rect;
        int shift = LivePillSlot.shift(r.getX(), r.getX() + r.getWidth(), r.getY(),
            LivePillSlot.bounds(graphics.guiWidth()));
        if (shift > 0) {
            r.setY(r.getY() + shift);
            applied = shift;
            shifted = r;
        }
        return false;
    }

    @Override
    public void afterRender(IBoxElement box, TooltipRect rect, GuiGraphics graphics, Accessor<?> accessor) {
        undo();
    }

    private void undo() {
        if (shifted != null) shifted.setY(shifted.getY() - applied);
        shifted = null;
        applied = 0;
    }
}
