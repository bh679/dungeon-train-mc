package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * The top-right pop-up raised every time a broadcast that went live ends — death, save &amp; quit,
 * the Live Eye coming off, a takeover, Free Play or a dead encoder. The toast layer keeps drawing
 * on the pause and title screens, so it is still seen after leaving the world.
 *
 * <p>Raised by {@link LiveStreamController} once the stream is already IDLE, so it is never part
 * of the broadcast. Its own {@link Toast} rather than an {@code AdvancementToast}, which Advancement
 * Plaques would swallow.</p>
 */
public final class LiveStreamEndedToast implements Toast {

    /** Why the stream ended; picks the second line. */
    public enum Reason {
        DIED("died"),
        LEFT("left"),
        REMOVED("removed"),
        REPLACED("replaced"),
        FREE_PLAY("free_play"),
        FAILED("failed");

        final String key;

        Reason(String suffix) {
            this.key = "toast.dungeontrain.live_ended." + suffix;
        }
    }

    private static final String TITLE_KEY = "toast.dungeontrain.live_ended.title";
    private static final ResourceLocation BACKGROUND_SPRITE = ResourceLocation.withDefaultNamespace("toast/advancement");
    private static final int HEADING_COLOR = 0xFFFF5555;
    private static final int REASON_COLOR = 0xFFAAAAAA;
    private static final int TEXT_X = 30;
    private static final long DISPLAY_MILLIS = 5000L;

    private final Reason reason;

    public LiveStreamEndedToast(Reason reason) {
        this.reason = reason;
    }

    @Override
    public Visibility render(GuiGraphics guiGraphics, ToastComponent toasts, long timeSinceLastVisible) {
        guiGraphics.blitSprite(BACKGROUND_SPRITE, 0, 0, width(), height());
        var font = toasts.getMinecraft().font;
        guiGraphics.drawString(font, Component.translatable(TITLE_KEY), TEXT_X, 7, HEADING_COLOR, false);
        guiGraphics.drawString(font, Component.translatable(reason.key), TEXT_X, 18, REASON_COLOR, false);
        guiGraphics.renderFakeItem(new ItemStack(ModItems.LIVE_HEADPIECE.get()), 8, 8);
        return timeSinceLastVisible >= DISPLAY_MILLIS * toasts.getNotificationDisplayTimeMultiplier()
            ? Visibility.HIDE : Visibility.SHOW;
    }
}
