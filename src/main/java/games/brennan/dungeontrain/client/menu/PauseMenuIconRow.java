package games.brennan.dungeontrain.client.menu;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.links.OfficialLinks;
import games.brennan.dungeontrain.client.localization.edit.ApprovedTranslationsFetcher;
import games.brennan.dungeontrain.client.localization.edit.TranslationContributor;
import games.brennan.dungeontrain.client.localization.edit.TranslationCoverageClient;
import games.brennan.dungeontrain.client.localization.edit.TranslationOutbox;
import games.brennan.dungeontrain.client.localization.edit.TranslationReviewNotes;
import games.brennan.dungeontrain.client.localization.edit.TranslationScreen;
import games.brennan.dungeontrain.client.localization.edit.TranslationTarget;
import games.brennan.dungeontrain.client.videos.VideosScreen;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The pause menu's icon strip: the title screen's icon column, laid flat on the right end of the
 * <b>Mods | Shaders</b> row — <b>Discord</b>, <b>Videos</b> and, when there is a language to
 * translate into, the <b>Help Translate</b> pencil. Mods and Shaders keep their labels and split
 * what is left of the row.
 *
 * <p>One handler for all three, for the reason {@code TitleScreenCreditsButton} gives: subscribers
 * at the same priority have no defined order, and each icon's position depends on how many sit
 * beside it. Runs at {@link EventPriority#LOWEST} because {@code PauseMenuLayoutHandler} halves the
 * Mods slot for Shaders at the default priority, and this re-lays that same span out. If either
 * anchor is missing (another mod rewrote the row) nothing is added — orphan icons floating where a
 * row used to be are worse than none.</p>
 *
 * <p>Discord used to be a text button on the Feedback / Report Bugs row; {@code PauseMenuLinksHandler}
 * now gives that whole row to Support the Mod. The translate pencil is hidden on {@code en_us} in a
 * release build, exactly like its title-screen and language-screen siblings — see
 * {@link TranslationTarget}. The editor itself already works in-world.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PauseMenuIconRow {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Component MODS_KEY = Component.translatable("fml.menu.mods");
    private static final Component SHADERS_LABEL = Component.translatable("gui.dungeontrain.shaders.button");
    private static final Component DISCORD_NARRATION = Component.translatable("gui.dungeontrain.discord_button");
    private static final Component VIDEOS_NARRATION = Component.translatable("gui.dungeontrain.videos.button");
    private static final Component TRANSLATE_LABEL = Component.translatable("gui.dungeontrain.translate.button");
    /** Same spacing {@code PauseMenuLayoutHandler} uses between Mods and Shaders. */
    private static final int GAP = 4;
    /** Icons sit two pixels under the row's buttons, centred on the row — same as the title column. */
    private static final int ICON_INSET = 1;
    /** Realms' pencil-and-paper icon — the same one {@code TitleScreenTranslateButton} uses. */
    private static final ResourceLocation EDIT_SPRITE =
        ResourceLocation.withDefaultNamespace("icon/draft_report");
    private static final int SPRITE_W = 15;
    private static final int SPRITE_H = 15;

    private PauseMenuIconRow() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen pauseScreen)) {
            return;
        }
        // F3+ESC pauses without building any menu — nothing to anchor to.
        if (!pauseScreen.showsPauseMenu()) {
            return;
        }
        // Builder worlds get a purpose-built pause menu with its own Discord button under the same
        // label; claiming it here would shrink that text button into an icon.
        if (games.brennan.dungeontrain.client.builder.BuilderPauseMenuHandler.handles(pauseScreen)) {
            return;
        }
        AbstractWidget mods = findWidget(event, MODS_KEY);
        AbstractWidget shaders = findWidget(event, SHADERS_LABEL);
        if (mods == null || shaders == null) {
            LOGGER.debug("PauseMenuIconRow: Mods/Shaders row not found (mods={}, shaders={}); skipping icons.",
                mods != null, shaders != null);
            return;
        }
        int size = mods.getHeight() - 2 * ICON_INSET;

        // A client that launched straight into a world may never have built the title
        // screen, so this can be the first chance to pick up relay-rotated links.
        OfficialLinks.ensureFetched();

        // Init.Post fires again on every window resize; re-place existing icons rather than
        // stacking twins on them. Left to right: Discord, Videos, Translate.
        List<AbstractWidget> icons = new ArrayList<>();
        icons.add(reuseOrAdd(event, DISCORD_NARRATION, () -> {
            DiscordIconButton discord = new DiscordIconButton(0, 0, size, DISCORD_NARRATION,
                b -> openDiscord(pauseScreen));
            discord.setTooltip(Tooltip.create(DISCORD_NARRATION));
            return discord;
        }));
        icons.add(reuseOrAdd(event, VIDEOS_NARRATION, () -> {
            VideosIconButton videos = new VideosIconButton(0, 0, size, VIDEOS_NARRATION,
                b -> openVideos(pauseScreen));
            videos.setTooltip(Tooltip.create(VIDEOS_NARRATION));
            return videos;
        }));
        String target = TranslationTarget.resolveForClient();
        if (!target.isEmpty()) {
            icons.add(translateButton(event, pauseScreen, target, size));
        }

        layoutRow(mods, shaders, icons, size);
    }

    /**
     * The Help Translate pencil, plus the once-per-session relay housekeeping the title screen does:
     * a client that launched straight into a world may never have built the title screen, so this
     * can be its first chance to drain the outbox and pick up the approved pool, coverage counts and
     * credits. Every call is idempotent and cheap to repeat, which matters — the pause menu is
     * rebuilt on every ESC press.
     */
    private static AbstractWidget translateButton(ScreenEvent.Init.Post event, PauseScreen pauseScreen,
                                                  String target, int size) {
        TranslationOutbox.get().flush();
        ApprovedTranslationsFetcher.fetchOnce();
        ApprovedTranslationsFetcher.fetchOnceFor(target);
        TranslationCoverageClient.fetchOnce();
        TranslationContributor.refreshOnce();

        AbstractWidget button = reuseOrAdd(event, TRANSLATE_LABEL, () ->
            SpriteIconButton.builder(TRANSLATE_LABEL, b -> openTranslate(pauseScreen, target), true)
                .width(size)
                .sprite(EDIT_SPRITE, SPRITE_W, SPRITE_H)
                .build());
        button.setTooltip(Tooltip.create(translateTooltip(target)));

        // Unread reviewer replies go on the tooltip only. The title screen also toasts them, but a
        // toast here would fire again on every ESC press until the editor was opened.
        TranslationReviewNotes.fetchOnce(() -> {
            int unread = TranslationReviewNotes.unreadCount();
            if (unread > 0) {
                button.setTooltip(Tooltip.create(translateTooltip(target).copy().append("\n").append(
                    Component.translatable("gui.dungeontrain.translate.replies.detail", unread))));
            }
        });
        return button;
    }

    /**
     * Re-lay the row out across the full slot: from Mods' left edge to the right edge of whatever
     * currently ends the row — Shaders on a fresh init, the last icon if a previous pass already
     * placed it. Icons are squares at the right end; Mods and Shaders split what is left equally.
     * Measured from the edges rather than from any one width, so a repeat pass derives the same
     * answer instead of shrinking the row each time.
     */
    private static void layoutRow(AbstractWidget mods, AbstractWidget shaders, List<AbstractWidget> icons,
                                  int size) {
        int left = mods.getX();
        int y = mods.getY();
        int right = 0;
        for (AbstractWidget widget : icons) {
            right = Math.max(right, widget.getX() + widget.getWidth());
        }
        right = Math.max(right, shaders.getX() + shaders.getWidth());
        int span = right - left;

        int iconSpan = icons.size() * (size + GAP);
        int textSpan = span - iconSpan;
        int halfW = (textSpan - GAP) / 2;

        mods.setX(left);
        mods.setY(y);
        mods.setWidth(halfW);
        shaders.setX(left + halfW + GAP);
        shaders.setY(y);
        shaders.setWidth(textSpan - halfW - GAP);

        int x = left + textSpan + GAP;
        for (AbstractWidget icon : icons) {
            icon.setX(x);
            icon.setY(y + ICON_INSET);
            icon.setWidth(size);
            icon.setHeight(size);
            x += size + GAP;
        }
    }

    /** Open the Discord invite through the vanilla confirm screen, returning to the pause menu. */
    private static void openDiscord(Screen parent) {
        UiAnalytics.click(UiAnalytics.SURFACE_PAUSE_MENU, UiAnalytics.TARGET_DISCORD);
        // Read at click time so a relay-served rotation still applies after the menu was built.
        String discordUrl = OfficialLinks.discord();
        Minecraft.getInstance().setScreen(new ConfirmLinkScreen(yes -> {
            UiAnalytics.confirm(UiAnalytics.SURFACE_PAUSE_MENU, UiAnalytics.TARGET_DISCORD, yes);
            if (yes) {
                Util.getPlatform().openUri(URI.create(discordUrl));
            }
            Minecraft.getInstance().setScreen(parent);
        }, discordUrl, true));
    }

    /** Open the Videos page — in-game, no link to confirm. */
    private static void openVideos(Screen parent) {
        UiAnalytics.click(UiAnalytics.SURFACE_PAUSE_MENU, UiAnalytics.TARGET_VIDEOS);
        Minecraft.getInstance().setScreen(new VideosScreen(parent));
    }

    private static void openTranslate(Screen parent, String target) {
        UiAnalytics.click(UiAnalytics.SURFACE_PAUSE_MENU, UiAnalytics.TARGET_TRANSLATE);
        Minecraft.getInstance().setScreen(new TranslationScreen(parent, target));
    }

    private static Component translateTooltip(String target) {
        return Component.translatable("gui.dungeontrain.translate.button.tooltip", target);
    }

    /** The widget already on the screen with this message, else a fresh one, added. */
    private static AbstractWidget reuseOrAdd(ScreenEvent.Init.Post event, Component message,
                                             Supplier<AbstractWidget> create) {
        AbstractWidget existing = findWidget(event, message);
        if (existing != null) {
            return existing;
        }
        AbstractWidget created = create.get();
        event.addListener(created);
        return created;
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
