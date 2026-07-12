package games.brennan.dungeontrain.platform.event;

import net.minecraft.client.gui.screens.Screen;

/**
 * Loader-neutral form of NeoForge's {@code ScreenEvent.Closing} (client game bus).
 * Fires as a screen closes. Not cancellable; handlers read the closing screen to
 * reset client-side state.
 */
@FunctionalInterface
public interface DtScreenClosingCallback {

    void onScreenClosing(Screen screen);
}
