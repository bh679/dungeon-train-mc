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
 * open a gap, and the button drops into that gap.</p>
 *
 * <p><b>An empty half-row is taken before a new one is opened.</b> Sable adds its own
 * "Sub-Level Settings…" button the same way, so with both mods installed the screen grew by two rows
 * and each sat alone with the other half of its row blank. If a lone half-width button is already
 * sitting on a row below the FOV slider, this button joins it instead of opening a second row —
 * which is both tidier and one row shorter, and does not name the other mod: any mod that opens a
 * row and leaves half of it empty gets shared with.</p>
 *
 * <p><b>And only when that still fits.</b> Vanilla's options screen is a fixed layout with no scrolling,
 * so nudging everything down by a row makes the screen one row taller than vanilla — and on a window
 * where vanilla only just fitted, the row that falls off the bottom is <b>Done</b>. A settings screen
 * you cannot leave by any visible means is far worse than a button in a less tidy place, and it was
 * reachable only by Esc. So the shift is measured first, and when it would push anything past the
 * bottom the button shares the footer row with Done instead, which is anchored to the bottom edge and
 * therefore always on screen.</p>
 *
 * <p>If the FOV slider can't be found it falls back to the same footer row; if Done can't be found
 * either it logs and leaves the screen untouched, mirroring the defensive stance of the other DT
 * screen handlers.</p>
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

    // LOWEST so every other mod's additions are already on the screen: the row-sharing check can
    // only pair with a button that exists by the time it looks.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onOptionsInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof OptionsScreen optionsScreen)) {
            return;
        }
        Button done = findButton(event, CommonComponents.GUI_DONE);
        AbstractWidget fov = findFovSlider(event);
        if (fov != null && addBesideLoneRow(event, optionsScreen, fov)) {
            return;
        }
        if (fov != null && shiftFits(event, optionsScreen, fov)) {
            addUnderFov(event, optionsScreen, fov);
            return;
        }
        if (done == null) {
            LOGGER.warn("OptionsScreenDungeonTrainButton: no room under the FOV slider and no Done "
                    + "button to sit beside; skipping the Dungeon Train button.");
            return;
        }
        if (fov != null) {
            LOGGER.info("OptionsScreenDungeonTrainButton: a row under FOV would push Done off a "
                    + "{}px screen; sharing the footer row instead.", optionsScreen.height);
        }
        addBesideDone(event, optionsScreen, done);
    }

    /**
     * Share a row another mod has already opened and half filled, rather than opening a second one.
     *
     * <p>Looks for a row below the FOV slider holding exactly one widget of the FOV slider's own
     * width — the signature of a half-width button sitting in a two-column row on its own — and puts
     * this button in the empty column beside it. Returns false when there is no such row, leaving
     * the caller to open one.</p>
     */
    private static boolean addBesideLoneRow(ScreenEvent.Init.Post event, OptionsScreen optionsScreen,
                                            AbstractWidget fov) {
        AbstractWidget right = rightOf(event, fov);
        if (right == null) {
            return false;
        }
        int fovBottom = fov.getY() + fov.getHeight();
        for (GuiEventListener listener : event.getListenersList()) {
            if (!(listener instanceof AbstractWidget lone) || lone.getY() < fovBottom
                    || lone.getWidth() != fov.getWidth()) {
                continue;
            }
            if (countOnRow(event, lone.getY()) != 1) {
                continue;
            }
            // Which column is free is decided by where the lone button actually sits, not by
            // assuming it took the left one.
            boolean loneIsLeft = lone.getX() == fov.getX();
            int x = loneIsLeft ? right.getX() : fov.getX();
            if (!loneIsLeft && lone.getX() != right.getX()) {
                continue; // in neither column — not a row this can share
            }
            LOGGER.info("OptionsScreenDungeonTrainButton: sharing the row opened by '{}' instead of "
                    + "adding another.", lone.getMessage().getString());
            event.addListener(dtButton(optionsScreen, x, lone.getY(), fov.getWidth(), lone.getHeight()));
            return true;
        }
        return false;
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

    /**
     * Would inserting a row still leave every widget on screen?
     *
     * <p>Measured against the lowest widget rather than against Done by name, because whether Done is
     * the lowest thing on the screen is a vanilla layout detail that another mod may already have
     * changed.</p>
     */
    private static boolean shiftFits(ScreenEvent.Init.Post event, OptionsScreen optionsScreen,
                                     AbstractWidget fov) {
        int step = fov.getHeight() + GAP;
        int fovBottom = fov.getY() + fov.getHeight();
        int lowest = 0;
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget w && w != fov && w.getY() >= fovBottom) {
                lowest = Math.max(lowest, w.getY() + w.getHeight() + step);
            }
        }
        return lowest + GAP <= optionsScreen.height;
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

    /** Halve the footer row: Dungeon Train on the left, Done on the right. Never grows the screen. */
    private static void addBesideDone(ScreenEvent.Init.Post event, OptionsScreen optionsScreen, Button done) {
        int halfW = (done.getWidth() - GAP) / 2;
        int doneX = done.getX() + halfW + GAP;
        done.setWidth(halfW);
        done.setX(doneX);
        event.addListener(dtButton(optionsScreen, done.getX() - halfW - GAP, done.getY(),
                halfW, done.getHeight()));
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
