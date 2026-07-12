package games.brennan.dungeontrain.platform.event;

import java.util.List;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;

/**
 * Carrier for {@link DtScreenInitCallback}, mirroring NeoForge's
 * {@code ScreenEvent.Init.Post}: read the initialised screen and add extra
 * widgets/listeners to it. {@link #addListener} keeps NeoForge's exact generic
 * bound so DT handlers add buttons unchanged; the bridge forwards to the live
 * event's {@code addListener}.
 */
public interface DtScreenInit {

    Screen getScreen();

    /** The screen's current listener/widget list (former {@code event.getListenersList()}). */
    List<GuiEventListener> getListenersList();

    <T extends GuiEventListener & Renderable & NarratableEntry> void addListener(T listener);
}
