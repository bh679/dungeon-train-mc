package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.PriorityBookSignToggle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paints the Priority Book's gold glow BEHIND the book on the sign screen, while its star is ticked
 * (see {@link PriorityBookSignToggle#drawGlow}).
 *
 * <p>Injected just before {@code renderBackground}'s one {@code blit} — the book texture — and after its
 * {@code renderTransparentBackground} dim, so the glow is bright and the book lands on top of it. Vanilla
 * never calls {@code super.renderBackground} here, so NeoForge's {@code BackgroundRendered} event cannot
 * be used for this.</p>
 */
@Mixin(BookEditScreen.class)
public abstract class BookEditScreenPriorityGlowMixin {

    @Inject(method = "renderBackground",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V"))
    private void dungeontrain$priorityGlowBehindBook(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                                     CallbackInfo ci) {
        PriorityBookSignToggle.drawGlow((BookEditScreen) (Object) this, graphics);
    }
}
