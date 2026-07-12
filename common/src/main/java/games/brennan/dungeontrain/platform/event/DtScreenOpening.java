package games.brennan.dungeontrain.platform.event;

import net.minecraft.client.gui.screens.Screen;

/**
 * Mutable/cancellable carrier for {@link DtScreenOpeningCallback}, mirroring
 * NeoForge's {@code ScreenEvent.Opening}. A handler may inspect the outgoing /
 * incoming screen, REPLACE the incoming screen ({@link #setNewScreen}), or cancel
 * the open entirely ({@link #setCanceled}). The bridge backs it with the live event
 * so every write behaves exactly as before.
 */
public interface DtScreenOpening {

    /** The screen currently open (base {@code ScreenEvent.getScreen()}). */
    Screen getScreen();

    /** The screen being replaced — {@code null} when opening straight from no screen. */
    Screen getCurrentScreen();

    /** The screen about to open. */
    Screen getNewScreen();

    /** Replace the screen about to open (former {@code event.setNewScreen}). */
    void setNewScreen(Screen newScreen);

    /** Cancel the open (former {@code event.setCanceled}). */
    void setCanceled(boolean canceled);
}
