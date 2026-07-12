package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.LetterEditorClient;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client mixin on the vanilla book edit/sign screen ({@code BookEditScreen#saveChanges(boolean)}).
 * When the player publishes — i.e. signs — a book ({@code publish == true}), flag it so the
 * lectern-letter close handler
 * ({@link games.brennan.dungeontrain.client.LetterLecternClientEvents}) knows NOT to send the
 * "leave the draft on the lectern" packet: a signed lectern book is consumed + burned server-side.
 *
 * <p>Setting the flag here — synchronously, before the screen closes and its
 * {@code ScreenEvent.Closing} fires — is the race-free discriminator between a sign and a plain close;
 * the server's own sign handling is async, so its ordering can't be relied on. Harmless for non-letter
 * book signs: {@link LetterEditorClient} only acts on the flag while a lectern editor is open, and
 * resets it on the next open.</p>
 */
@Mixin(BookEditScreen.class)
public abstract class BookEditScreenSignMixin {

    @Inject(method = "saveChanges", at = @At("HEAD"))
    private void dungeontrain$flagLetterSign(boolean publish, CallbackInfo ci) {
        if (publish) {
            LetterEditorClient.markSigned();
        }
    }
}
