package games.brennan.dungeontrain.compat.jade;

import games.brennan.dungeontrain.client.live.StreamTagObstruction;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import snownee.jade.api.Accessor;
import snownee.jade.api.callback.JadeAfterRenderCallback;
import snownee.jade.api.ui.IBoxElement;
import snownee.jade.api.ui.TooltipRect;

/**
 * Tells the stream's name tag where Jade's tooltip is each frame Jade draws it, so the tag drops
 * below it instead of hiding under it ({@link StreamTagObstruction}). Jade's rect is in GUI pixels,
 * already scaled.
 */
enum LiveEyeJadeClient implements JadeAfterRenderCallback {
    INSTANCE;

    @Override
    public void afterRender(IBoxElement box, TooltipRect rect, GuiGraphics graphics, Accessor<?> accessor) {
        Rect2i r = rect.rect;
        StreamTagObstruction.report(r.getX(), r.getX() + r.getWidth(), r.getY() + r.getHeight(),
            System.currentTimeMillis());
    }
}
