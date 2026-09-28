package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
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
 *
 * <p><b>A lone button below is pulled up beside this one.</b> Sable adds "Sub-Level Settings…" the
 * same way, so with both mods installed the screen grew by two rows and each button sat alone with
 * the other half of its row blank — and since vanilla's options screen is a fixed layout with no
 * scrolling, the second row is enough to push <b>Done</b> off the bottom on a window that only just
 * fitted vanilla. Any half-width button left alone on a row below is moved into the free column
 * beside this one and the rows below come back up, so two mods cost one row rather than two. No mod
 * is named: this pairs with whatever is there.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class OptionsScreenDungeonTrainButton {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** "Dungeon Train…" — the mod name stays as-is in every locale; the key exists so a locale can
     *  adjust the ellipsis or spacing around it. */
    private static final Component LABEL = Component.translatable("gui.dungeontrain.options.client.open");
    /** The vanilla FOV slider caption — its widget's message renders as "FOV: <value>". */
    private static final Component FOV_CAPTION = Component.translatable("options.fov");
    private static final int GAP = 4;

    private OptionsScreenDungeonTrainButton() {}

    // LOWEST so the other mods' buttons are already on the screen — the pairing below can only
    // move a button that exists by the time it looks.
    @SubscribeEvent(priority = EventPriority.LOWEST)
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

    /**
     * Put the DT button in a row under the FOV slider, moving the rows below only as far as needed.
     *
     * <p>Vanilla already leaves a gap between the FOV row and the grid beneath it. Pushing
     * everything down by a whole row regardless spent that gap twice: a band of dead space under the
     * button, and a screen a row taller than the window was sized for — which is what pushed
     * <b>Done</b> off the bottom. So the existing gap is measured and only the shortfall is taken.
     * Where vanilla's gap is already big enough, nothing below moves at all.</p>
     */
    private static void addUnderFov(ScreenEvent.Init.Post event, OptionsScreen optionsScreen, AbstractWidget fov) {
        int rowH = fov.getHeight();
        int fovBottom = fov.getY() + rowH;
        int rowY = fovBottom + GAP;

        int nextTop = topOfNextRow(event, fov, fovBottom);
        int shortfall = nextTop == Integer.MAX_VALUE ? 0 : Math.max(0, rowY + rowH + GAP - nextTop);
        if (shortfall > 0) {
            for (GuiEventListener listener : event.getListenersList()) {
                if (listener instanceof AbstractWidget w && w != fov && w.getY() >= fovBottom) {
                    w.setY(w.getY() + shortfall);
                }
            }
        }
        event.addListener(dtButton(optionsScreen, fov.getX(), rowY, fov.getWidth(), rowH));
        pairLoneRowInto(event, fov, rowY);
    }

    /** The top of the first row below the FOV slider, or {@code MAX_VALUE} if there is nothing. */
    private static int topOfNextRow(ScreenEvent.Init.Post event, AbstractWidget fov, int fovBottom) {
        int top = Integer.MAX_VALUE;
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget w && w != fov && w.getY() >= fovBottom) {
                top = Math.min(top, w.getY());
            }
        }
        return top;
    }

    /**
     * Move a button another mod left alone on its own row up into the free column of this one.
     *
     * <p>Matched by shape rather than by name — a widget of the FOV slider's width, alone on a row
     * below the one just opened — so it pairs with whatever is actually there. Everything that sat
     * below the row it vacates comes back up by a row, which is what stops the screen growing.</p>
     */
    private static void pairLoneRowInto(ScreenEvent.Init.Post event, AbstractWidget fov, int rowY) {
        AbstractWidget right = rightOf(event, fov);
        if (right == null) {
            return;
        }
        AbstractWidget lone = null;
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget w && w.getY() > rowY
                    && w.getWidth() == fov.getWidth() && countOnRow(event, w.getY()) == 1) {
                lone = w;
                break;
            }
        }
        if (lone == null) {
            return;
        }
        int vacated = lone.getY();
        // Close the row it leaves behind, measured from that row rather than assumed to be the
        // FOV row's height.
        int freed = lone.getHeight() + GAP;
        LOGGER.info("OptionsScreenDungeonTrainButton: pairing '{}' into the Dungeon Train row "
                + "instead of leaving it a row of its own.", lone.getMessage().getString());
        lone.setX(right.getX());
        lone.setY(rowY);
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget w && w != lone && w.getY() > vacated) {
                w.setY(w.getY() - freed);
            }
        }
    }

    /** The widget sharing the FOV slider's row to its right — the screen's right-hand column. */
    private static AbstractWidget rightOf(ScreenEvent.Init.Post event, AbstractWidget fov) {
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget w && w != fov && w.getY() == fov.getY()
                    && w.getX() > fov.getX()) {
                return w;
            }
        }
        return null;
    }

    private static int countOnRow(ScreenEvent.Init.Post event, int y) {
        int count = 0;
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget w && w.getY() == y) {
                count++;
            }
        }
        return count;
    }

    /** Grey — {@link DarkTintedButton}, the same tint the mod's own buttons carry elsewhere. */
    private static Button dtButton(OptionsScreen parent, int x, int y, int w, int h) {
        return new DarkTintedButton(x, y, w, h, LABEL,
                b -> Minecraft.getInstance().setScreen(new DungeonTrainClientOptionsScreen(parent)));
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
