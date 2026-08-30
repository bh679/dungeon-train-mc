package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * First screen of the world-join sequence — DT's stand-in for vanilla's
 * {@code GenericMessageScreen} while a world is being opened ("Reading world data", then
 * "Loading resources"), swapped in by {@link WorldOpenScreenSwap}.
 *
 * <p>It carries the same {@link LoadingScreenTheme} panel as the rest of the sequence — themed
 * world-load screen ({@code LevelLoadingScreenThemeMixin}), {@code ProgressScreen}
 * ({@code ProgressScreenThemeMixin}), {@code ReceivingLevelScreen} and
 * {@link CinematicLoadingScreen} — but it does not simply cut to it. This screen owns the
 * <b>hand-off</b> from the menu, on {@link JoinIntroFade}'s clock:</p>
 * <ol>
 *   <li>the screen the player pressed the button on keeps rendering, with the panorama drawn back
 *       over it at rising opacity — so its buttons, logo, splash and branding all dissolve
 *       together and leave the panorama they were sitting on;</li>
 *   <li>the themed panel then rises over that panorama, and is solid well before any real progress
 *       is on screen.</li>
 * </ol>
 *
 * <p>Fading the menu by re-drawing the panorama over it, rather than by turning down each widget's
 * alpha, is deliberate: {@code Screen.PANORAMA} is a single shared {@code PanoramaRenderer}, so the
 * overlay is the same backdrop the menu just painted (and keeps the same spin), whereas widget
 * alpha would have faded the buttons while the logo and splash text stayed solid and then popped.</p>
 *
 * <p>It has no progress model of its own: it renders the shared {@link LoadingSequenceProgress}
 * timeline's current value, opening that timeline (and its animation clock) at zero for the
 * world-load screen to drive upward. Purely presentational — the world-open flow drives itself,
 * exactly as it did behind the vanilla screen.</p>
 */
public final class WorldOpenLoadingScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The screen the join was started from, rendered underneath while it dissolves. Dropped once
     * the fade is past it — or the first time rendering it throws, since it has already been
     * {@code removed()} by the time we draw it and must never be able to break a world load.
     */
    @Nullable private Screen outgoing;

    public WorldOpenLoadingScreen(@Nullable Screen outgoing) {
        super(Component.translatable("gui.dungeontrain.loading.title"));
        this.outgoing = outgoing;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    protected boolean shouldNarrateNavigation() {
        return false;
    }

    /** The backdrop is drawn in {@link #render}, in step with the fade — nothing to do here. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float menuAlpha = JoinIntroFade.menuAlpha();
        if (menuAlpha > 0.0f && renderOutgoing(g, mouseX, mouseY, partialTick)) {
            // The menu drew the panorama itself; lay it back over the top to dissolve the chrome.
            PANORAMA.render(g, this.width, this.height, 1.0f - menuAlpha, partialTick);
        } else {
            PANORAMA.render(g, this.width, this.height, 1.0f, partialTick);
        }

        LoadingScreenTheme.drawPanel(g, this.font, this.width, this.height,
                LoadingSequenceProgress.displayed(), LoadingSequenceProgress.animNanos(),
                JoinIntroFade.themeAlpha());
    }

    /**
     * Draw the outgoing screen beneath the fade.
     *
     * @return true if it rendered — false once there is nothing left to fade, so the caller draws
     *         the panorama on its own instead.
     */
    private boolean renderOutgoing(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Screen screen = this.outgoing;
        if (screen == null) return false;
        try {
            screen.render(g, mouseX, mouseY, partialTick);
            return true;
        } catch (Throwable t) {
            // Rendering a screen that has already been removed is best-effort by nature. Log once,
            // drop it, and let every following frame take the panorama-only path — retrying a
            // render that throws would spam the log for the whole load.
            LOGGER.warn("Menu fade: outgoing screen {} failed to render; "
                    + "falling back to the panorama", screen.getClass().getName(), t);
            this.outgoing = null;
            return false;
        }
    }

    @Override
    public void removed() {
        super.removed();
        this.outgoing = null;
    }
}
