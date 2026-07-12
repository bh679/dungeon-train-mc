package games.brennan.dungeontrain.platform.event;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/**
 * Loader-neutral form of NeoForge's {@code ScreenEvent.Render.Post} (client game
 * bus, render thread). Not cancellable; the handler draws an overlay on top of the
 * screen. Both parameters are vanilla.
 */
@FunctionalInterface
public interface DtScreenRenderPostCallback {

    void onScreenRenderPost(Screen screen, GuiGraphics graphics);
}
