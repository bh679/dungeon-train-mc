package games.brennan.dungeontrain.client.localization.edit;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * Puts the translation editor on the pause menu, so a player can translate while riding the train
 * instead of going back to the title screen or three screens deep through Options → Language.
 *
 * <p>It joins the <b>Mods | Shaders</b> row as a third button — Mods | Shaders | Help Translate —
 * the row that already answers "what else is here besides the game". {@code PauseMenuLayoutHandler}
 * halves the Mods slot for Shaders at the default priority; this handler runs at
 * {@link EventPriority#LOWEST}, finds both by their translated labels, and re-lays the same span out
 * as thirds. If either is missing (another mod rewrote the row) nothing is added: an orphan third
 * button is worse than none.</p>
 *
 * <p>Hidden on {@code en_us} in a release build, exactly like the other two doorways — see
 * {@link TranslationTarget}. The editor itself already works in-world: book-prose edits are pushed
 * onto the integrated server by {@link IntegratedProseReload}, and every relay call is plain HTTP
 * with no dependency on which screen it was made from.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PauseMenuTranslateButton {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Component MODS_KEY = Component.translatable("fml.menu.mods");
    private static final Component SHADERS_LABEL = Component.translatable("gui.dungeontrain.shaders.button");
    private static final Component LABEL = Component.translatable("gui.dungeontrain.translate.button");
    /** Same spacing {@code PauseMenuLayoutHandler} uses between Mods and Shaders. */
    private static final int GAP = 4;
    private static final int COLUMNS = 3;

    private PauseMenuTranslateButton() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen pauseScreen)) {
            return;
        }
        // F3+ESC pauses without building any menu — nothing to anchor to.
        if (!pauseScreen.showsPauseMenu()) {
            return;
        }
        String target = TranslationTarget.resolveForClient();
        if (target.isEmpty()) {
            return; // English on a release build — the source language has nothing to translate
        }

        AbstractWidget mods = findWidget(event, MODS_KEY);
        AbstractWidget shaders = findWidget(event, SHADERS_LABEL);
        if (mods == null || shaders == null) {
            LOGGER.debug("Translate button: Mods/Shaders row not found on the pause menu (mods={}, shaders={}); skipping.",
                mods != null, shaders != null);
            return;
        }

        // A client that launched straight into a world may never have built the title screen, so
        // this can be the first chance to drain the outbox and pick up the relay's approved pool,
        // coverage counts and credits. Every call is once-per-session and cheap to repeat, which
        // matters here: the pause menu is rebuilt on every ESC press.
        TranslationOutbox.get().flush();
        ApprovedTranslationsFetcher.fetchOnce();
        ApprovedTranslationsFetcher.fetchOnceFor(target);
        TranslationCoverageClient.fetchOnce();
        TranslationContributor.refreshOnce();

        // Init.Post fires again on every window resize; re-place the existing button rather than
        // stacking a twin on it.
        AbstractWidget existing = findWidget(event, LABEL);
        DarkTintedButton button = existing instanceof DarkTintedButton reused ? reused
            : new DarkTintedButton(0, 0, 0, mods.getHeight(), LABEL, b -> openEditor(pauseScreen, target));
        layoutRow(mods, shaders, button, existing != null);
        button.setTooltip(Tooltip.create(tooltip(target)));
        if (existing == null) {
            event.addListener(button);
        }

        // Unread reviewer replies go on the tooltip only. The title screen also toasts them, but a
        // toast here would fire again on every ESC press until the editor was opened.
        TranslationReviewNotes.fetchOnce(() -> {
            int unread = TranslationReviewNotes.unreadCount();
            if (unread > 0) {
                button.setTooltip(Tooltip.create(tooltip(target).copy().append("\n").append(
                    Component.translatable("gui.dungeontrain.translate.replies.detail", unread))));
            }
        });
    }

    /**
     * Re-lay the row out as three equal cells across the full slot: from Mods' left edge to the
     * right edge of whichever button currently ends the row — Shaders on a fresh init, our own
     * button if a previous pass already placed it. Measured from those edges rather than from any
     * one width, so a repeat pass derives the same answer instead of shrinking the row a third at
     * a time.
     */
    private static void layoutRow(AbstractWidget mods, AbstractWidget shaders, AbstractWidget translate,
                                  boolean alreadyPlaced) {
        int left = mods.getX();
        AbstractWidget last = alreadyPlaced ? translate : shaders;
        int span = last.getX() + last.getWidth() - left;
        int cell = (span - GAP * (COLUMNS - 1)) / COLUMNS;
        int y = mods.getY();
        AbstractWidget[] row = {mods, shaders, translate};
        int x = left;
        for (AbstractWidget widget : row) {
            widget.setWidth(cell);
            widget.setX(x);
            widget.setY(y);
            x += cell + GAP;
        }
        // Give any rounding remainder to the last cell so the row still ends flush with the slot.
        translate.setWidth(left + span - translate.getX());
    }

    private static void openEditor(PauseScreen pauseScreen, String target) {
        UiAnalytics.click(UiAnalytics.SURFACE_PAUSE_MENU, UiAnalytics.TARGET_TRANSLATE);
        Minecraft.getInstance().setScreen(new TranslationScreen(pauseScreen, target));
    }

    private static Component tooltip(String target) {
        return Component.translatable("gui.dungeontrain.translate.button.tooltip", target);
    }

    private static AbstractWidget findWidget(ScreenEvent.Init.Post event, Component message) {
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget widget && message.equals(widget.getMessage())) {
                return widget;
            }
        }
        return null;
    }
}
