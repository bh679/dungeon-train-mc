package games.brennan.dungeontrain.client;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * The pop-up shown when a tracked advancement is ruled out for the rest of this life — the vanilla
 * advancement toast's frame, icon and layout, with a red "lost this life" heading and a red border
 * so it can't be mistaken for an earn.
 *
 * <p>Deliberately its own {@link Toast} rather than a re-styled {@code AdvancementToast}: Advancement
 * Plaques (shipped ON in the modpack) intercepts vanilla {@code AdvancementToast} instances and
 * would swallow a subclass into an earn plaque. A distinct class renders as-is beside it.</p>
 */
public final class DisqualifiedAdvancementToast implements Toast {

    private static final ResourceLocation BACKGROUND_SPRITE = ResourceLocation.withDefaultNamespace("toast/advancement");
    private static final String HEADING_KEY = "toast.dungeontrain.disqualified.title";
    private static final int HEADING_COLOR = 0xFFFF5555;
    private static final int BORDER_COLOR = 0xFFD62828;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int TEXT_X = 30;
    private static final int TITLE_WRAP_WIDTH = 125;
    private static final long DISPLAY_MILLIS = 5000L;

    private final AdvancementHolder advancement;
    private boolean playedSound;

    public DisqualifiedAdvancementToast(AdvancementHolder advancement) {
        this.advancement = advancement;
    }

    @Override
    public Visibility render(GuiGraphics guiGraphics, ToastComponent toasts, long timeSinceLastVisible) {
        DisplayInfo display = advancement.value().display().orElse(null);
        if (display == null) return Visibility.HIDE;

        guiGraphics.blitSprite(BACKGROUND_SPRITE, 0, 0, width(), height());
        drawBorder(guiGraphics);

        var font = toasts.getMinecraft().font;
        List<FormattedCharSequence> title = font.split(display.getTitle(), TITLE_WRAP_WIDTH);
        guiGraphics.drawString(font, Component.translatable(HEADING_KEY), TEXT_X, 7, HEADING_COLOR, false);
        // One title line fits below the heading; a wrapped title keeps its first line only — the
        // heading is the news here, the tile in the advancements screen carries the full text.
        if (!title.isEmpty()) {
            guiGraphics.drawString(font, title.get(0), TEXT_X, 18, TITLE_COLOR, false);
        }
        guiGraphics.renderFakeItem(display.getIcon(), 8, 8);

        if (!playedSound && timeSinceLastVisible > 0L) {
            playedSound = true;
            toasts.getMinecraft().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 1.0F, 0.6F));
        }
        return timeSinceLastVisible >= DISPLAY_MILLIS * toasts.getNotificationDisplayTimeMultiplier()
            ? Visibility.HIDE : Visibility.SHOW;
    }

    /** A 1px red frame just inside the sprite's own rounded border. */
    private void drawBorder(GuiGraphics g) {
        int w = width();
        int h = height();
        g.fill(1, 1, w - 1, 2, BORDER_COLOR);
        g.fill(1, h - 2, w - 1, h - 1, BORDER_COLOR);
        g.fill(1, 1, 2, h - 1, BORDER_COLOR);
        g.fill(w - 2, 1, w - 1, h - 1, BORDER_COLOR);
    }
}
