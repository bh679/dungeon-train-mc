package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.CopiedBookGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuses to paste text the supporters' Copy button produced into a Book & Quill, so that button reads
 * books but never duplicates them (see {@link CopiedBookGuard}).
 *
 * <p>Every paste on this screen goes through {@code keyPressed} (page, title, and DT's author line
 * alike), so one HEAD inject covers them all. Priority 900 puts it ahead of
 * {@code BookEditScreenAuthorMixin}'s own HEAD inject. The lectern-letter editor is this same screen.
 * A refused paste is swallowed and a toast says why; {@code addOrUpdate} keeps a held Ctrl+V to one.</p>
 */
@Mixin(value = BookEditScreen.class, priority = 900)
public abstract class BookEditScreenCopiedPasteMixin {

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$refuseCopiedBookPaste(int keyCode, int scanCode, int modifiers,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (!Screen.isPaste(keyCode)) return;
        Minecraft mc = Minecraft.getInstance();
        if (!CopiedBookGuard.isCopiedText(mc.keyboardHandler.getClipboard())) return;
        SystemToast.addOrUpdate(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable("gui.dungeontrain.book.paste_blocked"),
                Component.translatable("gui.dungeontrain.book.paste_blocked.body"));
        cir.setReturnValue(true);
    }
}
