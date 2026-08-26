package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.ClientBookSuspension;
import games.brennan.dungeontrain.client.EditorStatusHudOverlay;
import games.brennan.dungeontrain.narrative.BookSuspensionMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client mixin that refuses signing while this player's book uploads are paused — the visible half of
 * duplicate-book detection (see {@link ClientBookSuspension}, and the relay's {@code booksuspensions.js}).
 *
 * <p>Without it a suspended player's book screen looks perfectly normal: they write, they sign, the
 * book burns, and only then does a chat line tell them the train wanted none of it. Here the Sign
 * button (and the Finalize button, if the screen was already in signing mode when the window opened)
 * goes <b>red and dead</b>, with a tooltip naming the time left.</p>
 *
 * <p><b>Except inside an editor plot.</b> A book signed in a plot is authored CONTENT — it uploads
 * nothing and fires no live mechanic (see {@code EditorPlotScope} in the server-side intercept), so a
 * paused author is still free to write props. The client reads the editor status HUD's own
 * {@code isActive()}, which is exactly "the server says I am standing in a plot".</p>
 *
 * <p><b>Otherwise everything is blocked, including Death Notes and Love Notes</b> — they never upload, so this is
 * a deliberate policy choice rather than a technical one, and the server's own intercept
 * ({@code ServerGamePacketListenerImplSignBookMixin}) makes the same call so a client that ignores
 * this cannot get further. One rule, one sentence: while the train is refusing your writing, signing
 * is off. The lectern-letter editor is this same screen, so it is covered too.</p>
 *
 * <p>Injected at the RETURN of vanilla's own {@code updateButtonVisibility()} — the single place that
 * sets these buttons' {@code active}/{@code visible} — so nothing fights over the state. {@code tick()}
 * re-runs it, which is what lets the button free itself the moment the window lapses with the screen
 * still open, rather than the player having to close and reopen the book. A disabled vanilla button
 * passes grey only as a DEFAULT text colour, so the explicitly-styled red message still renders red.</p>
 */
@Mixin(BookEditScreen.class)
public abstract class BookEditScreenSuspensionMixin {

    @Shadow private Button signButton;
    @Shadow private Button finalizeButton;

    @Inject(method = "updateButtonVisibility", at = @At("RETURN"))
    private void dungeontrain$greyOutSigningWhileSuspended(CallbackInfo ci) {
        boolean suspended = ClientBookSuspension.isSuspended() && !dungeontrain$authoringInEditorPlot();
        dungeontrain$applyState(this.signButton, "book.signButton", suspended);
        dungeontrain$applyState(this.finalizeButton, "book.finalizeButton", suspended);
    }

    /** Re-evaluate every client tick so a lapsing window frees the button without reopening the book. */
    @Inject(method = "tick", at = @At("RETURN"))
    private void dungeontrain$refreshSuspensionState(CallbackInfo ci) {
        dungeontrain$greyOutSigningWhileSuspended(null);
    }

    /**
     * Red + inactive + explained while paused; vanilla's own label and enabled state otherwise. The
     * un-suspended branch never turns a button ON — it only restores the plain label and leaves
     * {@code active} as vanilla just set it, so the Finalize button's "needs a title" rule survives.
     */
    private static void dungeontrain$applyState(Button button, String vanillaKey, boolean suspended) {
        if (button == null) return;
        if (suspended) {
            button.active = false;
            button.setMessage(Component.translatable(vanillaKey).withStyle(ChatFormatting.RED));
            button.setTooltip(Tooltip.create(BookSuspensionMessage.signingPaused(
                    dungeontrain$locale(), ClientBookSuspension.remainingSec())));
            return;
        }
        button.setMessage(Component.translatable(vanillaKey));
        button.setTooltip(null);
    }

    /** True while the editor status HUD says this player is standing in an editor plot. */
    private static boolean dungeontrain$authoringInEditorPlot() {
        try {
            return EditorStatusHudOverlay.isActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** This client's own language, for the plural form of "30 seconds" (see PluralRules). */
    private static String dungeontrain$locale() {
        try {
            return Minecraft.getInstance().getLanguageManager().getSelected();
        } catch (Throwable t) {
            return "";
        }
    }
}
