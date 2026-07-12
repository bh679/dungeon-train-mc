package games.brennan.dungeontrain.platform.event;

import net.minecraft.client.gui.screens.Screen;

/**
 * Loader-neutral form of NeoForge's {@code ScreenEvent.Render.Pre} (client game bus,
 * render thread). Cancellable upstream, but DT's handlers only observe the screen
 * (never cancel vanilla rendering), so this is a {@code void} passthrough taking
 * just the screen the handlers read.
 */
@FunctionalInterface
public interface DtScreenRenderPreCallback {

    void onScreenRenderPre(Screen screen);
}
