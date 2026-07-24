package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * Injects a <b>"Dungeon Train…"</b> button into Minecraft's vanilla {@link OptionsScreen}, opening
 * {@link DungeonTrainClientOptionsScreen}. Because the main-menu Options and the Esc/pause Options are
 * the same {@code OptionsScreen} class, this single {@link ScreenEvent.Init.Post} hook surfaces the DT
 * client settings from both.
 *
 * <p>The button is placed <b>directly under the FOV slider</b> (matching its x / width): the FOV slider
 * is located by its caption ({@code options.fov}), the widgets below it are nudged down by one row to
 * open a gap, and the button drops into that gap. If the FOV slider can't be found it falls back to
 * anchoring above the vanilla <b>Done</b> button; if neither can be found it logs and leaves the screen
 * untouched, mirroring the defensive stance of the other DT screen handlers.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class OptionsScreenDungeonTrainButton {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Component LABEL = Component.literal("Dungeon Train…");
    /** The vanilla FOV slider caption — its widget's message renders as "FOV: <value>". */
    private static final Component FOV_CAPTION = Component.translatable("options.fov");
    private static final int GAP = 4;

    private OptionsScreenDungeonTrainButton() {}

    @SubscribeEvent
    public static void onOptionsInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof OptionsScreen optionsScreen)) {
            return;
        }
        AbstractWidget fov = findFovSlider(event);
        if (fov != null) {
            addUnderFov(event, optionsScreen, fov);
            return;
        }
        // FOV slider not found (layout changed by another mod) — fall back to just above Done.
        Button done = findButton(event, CommonComponents.GUI_DONE);
        if (done == null) {
            LOGGER.warn("OptionsScreenDungeonTrainButton: no FOV slider or Done button found; skipping the Dungeon Train button.");
            return;
        }
        event.addListener(dtButton(optionsScreen, done.getX(), done.getY() - done.getHeight() - GAP,
                done.getWidth(), done.getHeight()));
    }

    /** Insert the DT button in a fresh row right under the FOV slider, shifting the rows below it down. */
    private static void addUnderFov(ScreenEvent.Init.Post event, OptionsScreen optionsScreen, AbstractWidget fov) {
        int rowH = fov.getHeight();
        int step = rowH + GAP;
        int fovBottom = fov.getY() + rowH;
        // Nudge every widget that sits below the FOV slider down by one row to open the gap.
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget w && w != fov && w.getY() >= fovBottom) {
                w.setY(w.getY() + step);
            }
        }
        event.addListener(dtButton(optionsScreen, fov.getX(), fovBottom + GAP, fov.getWidth(), rowH));
    }

    private static Button dtButton(OptionsScreen parent, int x, int y, int w, int h) {
        return Button.builder(LABEL,
                        b -> Minecraft.getInstance().setScreen(new DungeonTrainClientOptionsScreen(parent)))
                .bounds(x, y, w, h).build();
    }

    /** The FOV slider: the widget whose message starts with the (localised) "FOV" caption. */
    private static AbstractWidget findFovSlider(ScreenEvent.Init.Post event) {
        String caption = FOV_CAPTION.getString();
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget w && w.getMessage().getString().startsWith(caption)) {
                return w;
            }
        }
        return null;
    }

    private static Button findButton(ScreenEvent.Init.Post event, Component message) {
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof Button button && message.equals(button.getMessage())) {
                return button;
            }
        }
        return null;
    }
}
