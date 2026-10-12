package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Takes the <a href="https://www.curseforge.com/minecraft/mc-mods/scribble">Scribble</a> mod's
 * <b>save book to file</b>, <b>load book from file</b> and <b>insert page</b> buttons off the
 * book-writing screen unless the player has switched them back on under Options &gt; Dungeon Train
 * ({@link ClientDisplayConfig#isScribbleFileButtonsVisible()},
 * {@link ClientDisplayConfig#isScribbleInsertPageButtonVisible()}, both off by default).
 *
 * <p>Same mechanism as {@link ScribbleColorPickerToggle}, for the same reason: Scribble rewrites
 * every one of its widgets' {@code visible} flag each time vanilla's
 * {@code updateButtonVisibility()} runs, so the buttons are unregistered in
 * {@link ScreenEvent.Init.Post} rather than hidden. Read that class for the full reasoning.</p>
 *
 * <p>All of Scribble's action buttons share one class ({@code IconButtonWidget}), so the class name
 * only narrows the search; each button is then told apart by its message's translation key, which
 * Scribble sets from fixed {@code text.scribble.action.*} keys. Matched by name, never by import —
 * Scribble is a modpack companion and is not on DT's compile classpath. Undo, redo and delete page
 * share the class and are deliberately left alone.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ScribbleBookButtonsToggle {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Set<String> FILE_BUTTON_KEYS = Set.of(
            "text.scribble.action.save_book_to_file",
            "text.scribble.action.load_book_from_file");

    private static final String INSERT_PAGE_KEY = "text.scribble.action.insert_new_page";

    /** Logged once per session — a Scribble rename would otherwise bring the buttons back silently. */
    private static boolean loggedDetection = false;

    private ScribbleBookButtonsToggle() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof BookEditScreen)) return;

        boolean keepFileButtons = ClientDisplayConfig.isScribbleFileButtonsVisible();
        boolean keepInsertPage = ClientDisplayConfig.isScribbleInsertPageButtonVisible();

        List<AbstractWidget> toRemove = new ArrayList<>();
        int matched = 0;
        for (GuiEventListener listener : event.getListenersList()) {
            if (!(listener instanceof AbstractWidget widget) || !isScribbleIconButton(widget.getClass())) continue;
            String key = translationKey(widget);
            if (FILE_BUTTON_KEYS.contains(key)) {
                matched++;
                if (!keepFileButtons) toRemove.add(widget);
            } else if (INSERT_PAGE_KEY.equals(key)) {
                matched++;
                if (!keepInsertPage) toRemove.add(widget);
            }
        }

        if (!loggedDetection) {
            loggedDetection = true;
            LOGGER.debug("ScribbleBookButtonsToggle: found {} of 3 Scribble file/insert-page button(s) on the book screen.",
                    matched);
        }

        toRemove.forEach(event::removeListener);
    }

    /** Covers both packages Scribble has used for the class (1.5.x {@code gui}, 2.x {@code gui.button}). */
    static boolean isScribbleIconButton(Class<?> type) {
        String name = type.getName();
        return name.startsWith("me.chrr.scribble.") && name.endsWith(".IconButtonWidget");
    }

    /** The widget message's translation key, or {@code ""} when it is not a translatable. */
    static String translationKey(AbstractWidget widget) {
        return widget.getMessage().getContents() instanceof TranslatableContents tc ? tc.getKey() : "";
    }
}
