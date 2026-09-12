package games.brennan.dungeontrain.client.localization.edit;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
 * <p>The same pencil icon the title screen carries ({@link TitleScreenTranslateButton}), in the
 * same relative spot: immediately left of the Options row. On the title screen that column is
 * where vanilla keeps its language and accessibility icons; the pause menu has no such column, so
 * the pencil stands alone there — but it is where a player who has seen it on the title screen
 * will look. Anchored to the Options button by its translated label, the way every other DT
 * pause-menu hook finds its slot, and skipped quietly if another mod has rewritten the menu.</p>
 *
 * <p>Runs at {@link EventPriority#LOWEST} so it reads Options' <em>final</em> position: the Train
 * Builder's pause menu ({@code BuilderPauseMenuHandler}) moves that button, and handlers at the
 * default priority have no defined order relative to each other.</p>
 *
 * <p>Hidden on {@code en_us} in a release build, exactly like the other two doorways — see
 * {@link TranslationTarget}. The editor itself already works in-world: book-prose edits are pushed
 * onto the integrated server by {@link IntegratedProseReload}, and every relay call is plain HTTP
 * with no dependency on which screen it was made from.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PauseMenuTranslateButton {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Component OPTIONS_KEY = Component.translatable("menu.options");
    private static final Component LABEL = Component.translatable("gui.dungeontrain.translate.button");
    private static final int GAP = 4;
    /** Same Realms pencil-and-paper sprite as the title screen — one icon for one editor. */
    private static final ResourceLocation EDIT_SPRITE =
        ResourceLocation.withDefaultNamespace("icon/draft_report");
    private static final int SPRITE_W = 15;
    private static final int SPRITE_H = 15;
    private static final int BUTTON_SIZE = 20;

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

        AbstractWidget options = findWidget(event, OPTIONS_KEY);
        if (options == null) {
            LOGGER.debug("Translate button: Options button not found on the pause menu; skipping.");
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
        SpriteIconButton button = existing instanceof SpriteIconButton reused ? reused : SpriteIconButton.builder(
                LABEL,
                b -> openEditor(pauseScreen, target),
                true)
            .width(BUTTON_SIZE)
            .sprite(EDIT_SPRITE, SPRITE_W, SPRITE_H)
            .build();
        button.setPosition(options.getX() - BUTTON_SIZE - GAP, options.getY());
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
